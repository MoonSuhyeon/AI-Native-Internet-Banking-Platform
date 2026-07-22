package com.bank.aigateway.audit;

import com.bank.aigateway.agent.AgenticLoop;
import com.bank.aigateway.audit.dto.AuditAnalysisRequest;
import com.bank.aigateway.audit.dto.AuditAnalysisResponse;
import com.bank.aigateway.llm.ToolAwareLlmClient;
import com.bank.aigateway.llm.agentic.ClaudeAgenticResponse;
import com.bank.aigateway.llm.agentic.ToolCall;
import com.bank.aigateway.llm.agentic.ToolDefinition;
import com.bank.aigateway.observability.GatewayMetrics;
import com.bank.aigateway.parser.AuditResponseParser;
import com.bank.aigateway.tool.AdvisoryHttpClient;
import com.bank.aigateway.tool.ToolRegistry;
import com.bank.aigateway.tool.executor.CohortStatsToolExecutor;
import com.bank.aigateway.tool.executor.PolicyCitationToolExecutor;
import com.bank.aigateway.tool.executor.ReviewerHistoryToolExecutor;
import com.bank.aigateway.tool.executor.SimilarCasesToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Agentic RAG 전체 흐름 회귀 테스트.
 *
 * 시나리오:
 *   1턴: LLM → get_policy_citation tool 요청
 *   executor: AdvisoryHttpClient(mock) → 정책 청크 JSON 반환
 *   2턴: LLM → end_turn + {"conclusion":"VIOLATION_SUSPECTED","citedChunkIds":[77,88]}
 *
 * 검증:
 *   - tool executor 가 올바른 경로로 호출되었는지
 *   - AuditAnalysisResponse.citedChunkIds 에 [77, 88] 포함
 *   - AuditAnalysisResponse.conclusion == VIOLATION_SUSPECTED
 *
 * Path C: 모든 4 tool executor 가 advisory-service 를 직접 호출한다.
 */
class AgenticRagFlowTest {

    ObjectMapper        objectMapper   = new ObjectMapper();
    ToolAwareLlmClient  llmClient      = mock(ToolAwareLlmClient.class);
    AdvisoryHttpClient  advisoryClient = mock(AdvisoryHttpClient.class);
    GatewayMetrics      metrics        = mock(GatewayMetrics.class);

    AgenticAuditAnalysisService service;

    @BeforeEach
    void setUp() {
        PolicyCitationToolExecutor  policyExec  = new PolicyCitationToolExecutor(advisoryClient, objectMapper);
        SimilarCasesToolExecutor    similarExec = new SimilarCasesToolExecutor(advisoryClient, objectMapper);
        ReviewerHistoryToolExecutor historyExec = new ReviewerHistoryToolExecutor(advisoryClient, objectMapper);
        CohortStatsToolExecutor     cohortExec  = new CohortStatsToolExecutor(advisoryClient, objectMapper);

        ToolRegistry toolRegistry = new ToolRegistry(
                List.of(policyExec, similarExec, historyExec, cohortExec));
        AgenticLoop   agenticLoop = new AgenticLoop(llmClient, objectMapper, new com.bank.aigateway.agent.AgenticLoopProperties(5));
        AuditResponseParser parser = new AuditResponseParser(objectMapper);

        service = new AgenticAuditAnalysisService(agenticLoop, toolRegistry, parser, metrics);
        when(metrics.startAnalysisTimer()).thenReturn(mock(Timer.Sample.class));
    }

    @Test
    void get_policy_citation_tool_호출_후_citedChunkIds_포함_결론_반환() {
        // 1턴: get_policy_citation tool 요청
        ObjectNode toolInput = objectMapper.createObjectNode();
        toolInput.put("query", "DSR 한도 초과 예외처리");
        ToolCall policyCall = new ToolCall("tc-001", PolicyCitationToolExecutor.TOOL_NAME, toolInput);
        when(llmClient.completeWithTools(any(), any(), any()))
                .thenReturn(toolUseResponse(policyCall, 120, 60))
                .thenReturn(endTurnWithCitations(200, 90));

        // executor → advisory-service mock 응답 (Path C: 모든 tool 이 advisory 직접 호출)
        when(advisoryClient.get(contains("/api/internal/advisory/policy-citations")))
                .thenReturn(Optional.of("""
                        {"totalCount":2,"citations":[
                          {"chunkId":77,"docCd":"FAIR_LENDING_001","score":0.91,"chunkText":"DSR 기준"},
                          {"chunkId":88,"docCd":"HMDA_STAT_001","score":0.83,"chunkText":"편향 통계"}
                        ]}"""));

        AuditAnalysisRequest req = new AuditAnalysisRequest(
                "COMPLIANCE_VERIFICATION", 5001L, 301L, null, List.of(), List.of());

        AuditAnalysisResponse resp = service.analyze(req);

        assertThat(resp.conclusion()).isEqualTo("VIOLATION_SUSPECTED");
        assertThat(resp.confidenceScore()).isEqualTo(0.88);
        assertThat(resp.citedChunkIds()).containsExactlyInAnyOrder(77L, 88L);
        assertThat(resp.inputTokens()).isEqualTo(320);
        assertThat(resp.outputTokens()).isEqualTo(150);
        verify(advisoryClient).get(contains("/api/internal/advisory/policy-citations"));
    }

    @Test
    void get_similar_cases_tool_호출_후_결론_반환() {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("rev_id", 5002L);
        ToolCall similarCall = new ToolCall("tc-002", SimilarCasesToolExecutor.TOOL_NAME, input);
        when(llmClient.completeWithTools(any(), any(), any()))
                .thenReturn(toolUseResponse(similarCall, 100, 50))
                .thenReturn(endTurnNoCitations(180, 80));

        when(advisoryClient.get(contains("/api/internal/advisory/similar-cases")))
                .thenReturn(Optional.of("""
                        {"totalCount":1,"cases":[
                          {"caseIdxId":55,"revId":4001,"decisionCd":"REJECTED","score":0.79}
                        ]}"""));

        AuditAnalysisRequest req = new AuditAnalysisRequest(
                "BIAS_DETECTION", 5002L, 302L, null, List.of(), List.of());

        AuditAnalysisResponse resp = service.analyze(req);

        assertThat(resp.conclusion()).isEqualTo("BIAS_SUSPECTED");
        assertThat(resp.citedChunkIds()).isEmpty();
        verify(advisoryClient).get(contains("/api/internal/advisory/similar-cases?revId=5002"));
    }

    @Test
    void tool_응답_빈_경우에도_루프_완료() {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("query", "연령 편향");
        ToolCall call = new ToolCall("tc-003", PolicyCitationToolExecutor.TOOL_NAME, input);
        when(llmClient.completeWithTools(any(), any(), any()))
                .thenReturn(toolUseResponse(call, 80, 40))
                .thenReturn(endTurnNoCitations(100, 50));

        // advisory 호출 실패 → executor 빈 문자열 반환
        when(advisoryClient.get(contains("policy-citations"))).thenReturn(Optional.empty());

        AuditAnalysisResponse resp = service.analyze(
                new AuditAnalysisRequest("BIAS_DETECTION", 5003L, 303L, null, List.of(), List.of()));

        assertThat(resp.conclusion()).isEqualTo("BIAS_SUSPECTED");
        assertThat(resp.citedChunkIds()).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ClaudeAgenticResponse toolUseResponse(ToolCall call, int input, int output) {
        ArrayNode content = objectMapper.createArrayNode();
        content.addObject().put("type", "text").put("text", "도구를 조회합니다.");
        ObjectNode block = content.addObject();
        block.put("type", "tool_use");
        block.put("id", call.id());
        block.put("name", call.name());
        block.set("input", call.input());
        return new ClaudeAgenticResponse("tool_use", content, null, List.of(call), input, output);
    }

    private ClaudeAgenticResponse endTurnWithCitations(int input, int output) {
        String text = """
                {
                  "conclusion": "VIOLATION_SUSPECTED",
                  "reasoningSummary": "DSR 한도 초과 예외 처리 규정 위반 의심",
                  "confidenceScore": 0.88,
                  "citedChunkIds": [77, 88]
                }""";
        ArrayNode content = objectMapper.createArrayNode();
        content.addObject().put("type", "text").put("text", text);
        return new ClaudeAgenticResponse("end_turn", content, text, List.of(), input, output);
    }

    private ClaudeAgenticResponse endTurnNoCitations(int input, int output) {
        String text = """
                {
                  "conclusion": "BIAS_SUSPECTED",
                  "reasoningSummary": "유사 사례 대비 거절률 편향 의심",
                  "confidenceScore": 0.72,
                  "citedChunkIds": []
                }""";
        ArrayNode content = objectMapper.createArrayNode();
        content.addObject().put("type", "text").put("text", text);
        return new ClaudeAgenticResponse("end_turn", content, text, List.of(), input, output);
    }
}
