package com.bank.aigateway.agent;

import com.bank.aigateway.llm.ToolAwareLlmClient;
import com.bank.aigateway.llm.agentic.ClaudeAgenticResponse;
import com.bank.aigateway.llm.agentic.ToolCall;
import com.bank.aigateway.llm.agentic.ToolDefinition;
import com.bank.aigateway.tool.PiiMaskingUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * Claude tool-use agentic loop.
 *
 * stop_reason=tool_use 응답 시 tool 실행 → tool_result 추가 → 재호출을 반복.
 * maxTurns({@code aigateway.agent.max-turns}) 초과 시 INSUFFICIENT_DATA JSON 반환.
 */
@Slf4j
@Component
@EnableConfigurationProperties(AgenticLoopProperties.class)
@RequiredArgsConstructor
public class AgenticLoop {

    static final String FALLBACK_JSON = """
            {
              "conclusion": "INSUFFICIENT_DATA",
              "reasoningSummary": "최대 분석 횟수에 도달하여 충분한 정보를 수집하지 못했습니다.",
              "confidenceScore": 0.0
            }
            """;

    private final ToolAwareLlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final AgenticLoopProperties loopProps;

    /**
     * @param systemPrompt  Claude system prompt
     * @param userMessage   초기 user 메시지
     * @param tools         노출할 tool 목록
     * @param toolExecutor  tool name → 실행 결과 문자열 (실패 시 에러 JSON 반환)
     */
    public AgenticLoopResult run(String systemPrompt,
                                 String userMessage,
                                 List<ToolDefinition> tools,
                                 Function<ToolCall, String> toolExecutor) {
        int maxTurns = loopProps.maxTurns();
        ArrayNode messages = buildInitialMessages(userMessage);
        int totalInput  = 0;
        int totalOutput = 0;

        for (int turn = 0; turn < maxTurns; turn++) {
            ClaudeAgenticResponse resp = llmClient.completeWithTools(systemPrompt, messages, tools);
            totalInput  += resp.inputTokens();
            totalOutput += resp.outputTokens();

            if (resp.isEndTurn()) {
                log.info("AgenticLoop 완료 — turns={} inputTokens={} outputTokens={}", turn + 1, totalInput, totalOutput);
                return new AgenticLoopResult(resp.textContent(), totalInput, totalOutput, turn + 1, false);
            }

            if (resp.isToolUse()) {
                appendAssistantMessage(messages, resp.rawContentNodes());
                appendToolResults(messages, resp.toolCalls(), toolExecutor);
            }
        }

        log.warn("AgenticLoop maxTurns({}) 초과 — INSUFFICIENT_DATA 반환", maxTurns);
        return new AgenticLoopResult(FALLBACK_JSON, totalInput, totalOutput, maxTurns, true);
    }

    private ArrayNode buildInitialMessages(String userMessage) {
        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        return messages;
    }

    private void appendAssistantMessage(ArrayNode messages, ArrayNode contentNodes) {
        ObjectNode msg = messages.addObject();
        msg.put("role", "assistant");
        msg.set("content", contentNodes);
    }

    private void appendToolResults(ArrayNode messages, List<ToolCall> toolCalls,
                                   Function<ToolCall, String> executor) {
        ArrayNode resultContent = objectMapper.createArrayNode();
        for (ToolCall call : toolCalls) {
            String result = executeQuietly(call, executor);
            ObjectNode block = resultContent.addObject();
            block.put("type", "tool_result");
            block.put("tool_use_id", call.id());
            block.put("content", result);
        }
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.set("content", resultContent);
    }

    private String executeQuietly(ToolCall call, Function<ToolCall, String> executor) {
        try {
            String result = executor.apply(call);
            // tool 결과에 포함된 PII 가 LLM 에 전달되지 않도록 마스킹
            return PiiMaskingUtil.mask(result != null ? result : "");
        } catch (Exception e) {
            log.warn("Tool 실행 실패 — tool={}: {}", call.name(), e.getMessage());
            return "{\"error\":\"tool_execution_failed\",\"tool\":\"%s\",\"reason\":\"%s\"}"
                    .formatted(call.name(), e.getMessage() != null ? e.getMessage().replace("\"", "'") : "unknown");
        }
    }
}
