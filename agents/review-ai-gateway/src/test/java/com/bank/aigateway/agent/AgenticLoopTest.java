package com.bank.aigateway.agent;

import com.bank.aigateway.llm.ToolAwareLlmClient;
import com.bank.aigateway.llm.agentic.ClaudeAgenticResponse;
import com.bank.aigateway.llm.agentic.ToolCall;
import com.bank.aigateway.llm.agentic.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgenticLoopTest {

    static final int TEST_MAX_TURNS = 5;

    ObjectMapper objectMapper = new ObjectMapper();
    ToolAwareLlmClient llmClient = mock(ToolAwareLlmClient.class);
    AgenticLoop loop;

    @BeforeEach
    void setUp() {
        loop = new AgenticLoop(llmClient, objectMapper, new AgenticLoopProperties(TEST_MAX_TURNS));
    }

    @Test
    void 첫_응답이_end_turn이면_1턴으로_반환() {
        when(llmClient.completeWithTools(any(), any(), any()))
                .thenReturn(endTurnResponse("최종 감사 의견", 100, 80));

        AgenticLoopResult result = loop.run("system", "user msg", List.of(), call -> "");

        assertThat(result.text()).isEqualTo("최종 감사 의견");
        assertThat(result.turnsUsed()).isEqualTo(1);
        assertThat(result.inputTokens()).isEqualTo(100);
        assertThat(result.outputTokens()).isEqualTo(80);
        assertThat(result.timedOut()).isFalse();
        verify(llmClient, times(1)).completeWithTools(any(), any(), any());
    }

    @Test
    void tool_use_후_end_turn이면_2턴으로_반환() {
        ToolCall call = new ToolCall("id-1", "get_policy_citation", objectMapper.createObjectNode());
        AtomicInteger executorCallCount = new AtomicInteger(0);

        when(llmClient.completeWithTools(any(), any(), any()))
                .thenReturn(toolUseResponse(call, 50, 30))
                .thenReturn(endTurnResponse("tool 조회 후 의견", 120, 90));

        AgenticLoopResult result = loop.run("system", "user msg", List.of(), tc -> {
            executorCallCount.incrementAndGet();
            return "정책 문서 내용";
        });

        assertThat(result.text()).isEqualTo("tool 조회 후 의견");
        assertThat(result.turnsUsed()).isEqualTo(2);
        assertThat(result.inputTokens()).isEqualTo(170);
        assertThat(result.outputTokens()).isEqualTo(120);
        assertThat(executorCallCount.get()).isEqualTo(1);
    }

    @Test
    void maxTurns_초과_시_INSUFFICIENT_DATA_반환() {
        ToolCall call = new ToolCall("id-x", "get_similar_cases", objectMapper.createObjectNode());
        when(llmClient.completeWithTools(any(), any(), any()))
                .thenReturn(toolUseResponse(call, 50, 30));

        AgenticLoopResult result = loop.run("system", "user msg", List.of(), tc -> "some result");

        assertThat(result.text()).contains("INSUFFICIENT_DATA");
        assertThat(result.turnsUsed()).isEqualTo(TEST_MAX_TURNS);
        assertThat(result.timedOut()).isTrue();
        verify(llmClient, times(TEST_MAX_TURNS)).completeWithTools(any(), any(), any());
    }

    @Test
    void maxTurns_설정값이_작으면_해당_턴에_폴백() {
        ToolCall call = new ToolCall("id-t", "get_policy_citation", objectMapper.createObjectNode());
        when(llmClient.completeWithTools(any(), any(), any()))
                .thenReturn(toolUseResponse(call, 50, 30));

        var tightLoop = new AgenticLoop(llmClient, objectMapper, new AgenticLoopProperties(2));
        AgenticLoopResult result = tightLoop.run("system", "user msg", List.of(), tc -> "result");

        assertThat(result.timedOut()).isTrue();
        assertThat(result.turnsUsed()).isEqualTo(2);
        verify(llmClient, times(2)).completeWithTools(any(), any(), any());
    }

    @Test
    void tool_실행_예외_시_에러_JSON으로_LLM에_알리고_루프_계속() {
        ToolCall call = new ToolCall("id-2", "get_reviewer_history", objectMapper.createObjectNode());
        ArgumentCaptor<ArrayNode> messagesCaptor = ArgumentCaptor.forClass(ArrayNode.class);

        when(llmClient.completeWithTools(any(), messagesCaptor.capture(), any()))
                .thenReturn(toolUseResponse(call, 50, 30))
                .thenReturn(endTurnResponse("예외 후 계속된 의견", 100, 70));

        AgenticLoopResult result = loop.run("system", "user msg", List.of(), tc -> {
            throw new RuntimeException("네트워크 오류");
        });

        assertThat(result.text()).isEqualTo("예외 후 계속된 의견");
        assertThat(result.turnsUsed()).isEqualTo(2);

        // 두 번째 LLM 호출 시 전달된 messages에 에러 JSON이 포함돼야 한다
        ArrayNode secondCallMessages = messagesCaptor.getAllValues().get(1);
        String messagesJson = secondCallMessages.toString();
        assertThat(messagesJson).contains("tool_execution_failed");
        assertThat(messagesJson).contains("get_reviewer_history");
    }

    @Test
    void tool_결과에_PII_포함_시_LLM_메시지에_마스킹되어_전달() {
        ToolCall call = new ToolCall("id-pii", "get_reviewer_history", objectMapper.createObjectNode());
        ArgumentCaptor<ArrayNode> captor = ArgumentCaptor.forClass(ArrayNode.class);

        when(llmClient.completeWithTools(any(), captor.capture(), any()))
                .thenReturn(toolUseResponse(call, 50, 30))
                .thenReturn(endTurnResponse("결론", 100, 70));

        loop.run("system", "user msg", List.of(), tc ->
                "심사관 홍길동씨 주민번호 901010-1234567 전화번호 010-1234-5678 승인율 72%");

        // 두 번째 LLM 호출(tool_result 포함)에서 PII 가 마스킹됐어야 한다
        ArrayNode secondMessages = captor.getAllValues().get(1);
        String json = secondMessages.toString();
        assertThat(json).doesNotContain("901010-1234567");
        assertThat(json).doesNotContain("010-1234-5678");
        assertThat(json).doesNotContain("홍길동씨");
        assertThat(json).contains("[RRN]");
        assertThat(json).contains("[PHONE]");
        assertThat(json).contains("[NAME]");
        assertThat(json).contains("72%");   // 비PII 원문 유지
    }

    @Test
    void 한_응답에_복수_tool_call이면_모두_실행() {
        ToolCall call1 = new ToolCall("id-a", "get_policy_citation", objectMapper.createObjectNode());
        ToolCall call2 = new ToolCall("id-b", "get_cohort_stats", objectMapper.createObjectNode());
        AtomicInteger executorCallCount = new AtomicInteger(0);

        when(llmClient.completeWithTools(any(), any(), any()))
                .thenReturn(multiToolUseResponse(List.of(call1, call2), 60, 40))
                .thenReturn(endTurnResponse("복수 tool 후 의견", 150, 100));

        loop.run("system", "user msg", List.of(), tc -> {
            executorCallCount.incrementAndGet();
            return "결과";
        });

        assertThat(executorCallCount.get()).isEqualTo(2);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private ClaudeAgenticResponse endTurnResponse(String text, int input, int output) {
        ArrayNode content = objectMapper.createArrayNode();
        content.addObject().put("type", "text").put("text", text);
        return new ClaudeAgenticResponse("end_turn", content, text, List.of(), input, output);
    }

    private ClaudeAgenticResponse toolUseResponse(ToolCall call, int input, int output) {
        return multiToolUseResponse(List.of(call), input, output);
    }

    private ClaudeAgenticResponse multiToolUseResponse(List<ToolCall> calls, int input, int output) {
        ArrayNode content = objectMapper.createArrayNode();
        content.addObject().put("type", "text").put("text", "도구를 사용해 추가 조사합니다.");
        for (ToolCall call : calls) {
            ObjectNode block = content.addObject();
            block.put("type", "tool_use");
            block.put("id", call.id());
            block.put("name", call.name());
            block.set("input", call.input());
        }
        return new ClaudeAgenticResponse("tool_use", content, null, calls, input, output);
    }
}
