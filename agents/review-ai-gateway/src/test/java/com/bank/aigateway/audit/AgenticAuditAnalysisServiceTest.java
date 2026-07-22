package com.bank.aigateway.audit;

import com.bank.aigateway.agent.AgenticLoop;
import com.bank.aigateway.agent.AgenticLoopResult;
import com.bank.aigateway.audit.dto.AuditAnalysisRequest;
import com.bank.aigateway.audit.dto.AuditAnalysisResponse;
import com.bank.aigateway.audit.dto.SignalSummary;
import com.bank.aigateway.llm.agentic.ToolDefinition;
import com.bank.aigateway.observability.GatewayMetrics;
import com.bank.aigateway.parser.AuditResponseParser;
import com.bank.aigateway.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class AgenticAuditAnalysisServiceTest {

    AgenticLoop       agenticLoop  = mock(AgenticLoop.class);
    ToolRegistry      toolRegistry = mock(ToolRegistry.class);
    AuditResponseParser parser     = mock(AuditResponseParser.class);
    GatewayMetrics    metrics      = mock(GatewayMetrics.class);

    AgenticAuditAnalysisService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new AgenticAuditAnalysisService(agenticLoop, toolRegistry, parser, metrics);
        when(metrics.startAnalysisTimer()).thenReturn(mock(Timer.Sample.class));
        ObjectMapper om = new ObjectMapper();
        when(toolRegistry.allDefinitions()).thenReturn(
                List.of(new ToolDefinition("get_policy_citation", "설명",
                        om.readTree("{\"type\":\"object\"}")))
        );
        when(toolRegistry.asExecutorFunction()).thenReturn(call -> "");
    }

    @Test
    void BIAS_DETECTION_분석_정상_반환() {
        AuditAnalysisRequest req = biasRequest();
        when(agenticLoop.run(any(), any(), any(), any()))
                .thenReturn(new AgenticLoopResult("{}", 100, 80, 2, false));
        when(parser.parse("{}"))
                .thenReturn(new AuditResponseParser.ParsedAuditResult("BIAS_SUSPECTED", "편향 의심", 0.85, List.of()));

        AuditAnalysisResponse resp = service.analyze(req);

        assertThat(resp.analysisType()).isEqualTo("BIAS_DETECTION");
        assertThat(resp.conclusion()).isEqualTo("BIAS_SUSPECTED");
        assertThat(resp.confidenceScore()).isEqualTo(0.85);
        assertThat(resp.inputTokens()).isEqualTo(100);
        assertThat(resp.outputTokens()).isEqualTo(80);
    }

    @Test
    void COMPLIANCE_VERIFICATION_분석_정상_반환() {
        AuditAnalysisRequest req = complianceRequest();
        when(agenticLoop.run(any(), any(), any(), any()))
                .thenReturn(new AgenticLoopResult("{}", 120, 90, 1, false));
        when(parser.parse("{}"))
                .thenReturn(new AuditResponseParser.ParsedAuditResult("COMPLIANT", "규정 준수", 0.93, List.of()));

        AuditAnalysisResponse resp = service.analyze(req);

        assertThat(resp.analysisType()).isEqualTo("COMPLIANCE_VERIFICATION");
        assertThat(resp.conclusion()).isEqualTo("COMPLIANT");
    }

    @Test
    void 미지원_analysisType은_IllegalArgumentException() {
        AuditAnalysisRequest req = new AuditAnalysisRequest(
                "UNKNOWN", 1L, 1L, null, List.of(), List.of());

        assertThatThrownBy(() -> service.analyze(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN");
    }

    @Test
    void agenticLoop에_toolRegistry_정의_전달() {
        AuditAnalysisRequest req = biasRequest();
        when(agenticLoop.run(any(), any(), any(), any()))
                .thenReturn(new AgenticLoopResult("{}", 50, 40, 1, false));
        when(parser.parse(any()))
                .thenReturn(new AuditResponseParser.ParsedAuditResult("NO_BIAS_DETECTED", "", 0.9, List.of()));

        service.analyze(req);

        verify(agenticLoop).run(any(), any(), any(), any());
        verify(toolRegistry, atLeastOnce()).allDefinitions();
        verify(toolRegistry, atLeastOnce()).asExecutorFunction();
    }

    @Test
    void 토큰_메트릭_기록() {
        AuditAnalysisRequest req = biasRequest();
        when(agenticLoop.run(any(), any(), any(), any()))
                .thenReturn(new AgenticLoopResult("{}", 300, 150, 3, false));
        when(parser.parse(any()))
                .thenReturn(new AuditResponseParser.ParsedAuditResult("BIAS_SUSPECTED", "", 0.7, List.of()));

        service.analyze(req);

        verify(metrics).recordTokens("BIAS_DETECTION", 300, 150);
        verify(metrics).recordAnalysisDuration(any(), eq("BIAS_DETECTION"));
    }

    @Test
    void 신호_있는_요청_userMessage에_신호_포함() {
        AuditAnalysisRequest req = new AuditAnalysisRequest(
                "BIAS_DETECTION", 9001L, 201L, "심사 의견",
                List.of(new SignalSummary("BIAS_DETECTION", "WARN", "approve_rate", 0.62, 0.80)),
                List.of()
        );
        when(agenticLoop.run(any(), any(), any(), any()))
                .thenReturn(new AgenticLoopResult("{}", 100, 80, 1, false));
        when(parser.parse(any()))
                .thenReturn(new AuditResponseParser.ParsedAuditResult("BIAS_SUSPECTED", "", 0.8, List.of()));

        service.analyze(req);

        verify(agenticLoop).run(
                any(),
                argThat(msg -> msg.contains("approve_rate") && msg.contains("심사 의견")),
                any(), any()
        );
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private AuditAnalysisRequest biasRequest() {
        return new AuditAnalysisRequest("BIAS_DETECTION", 9001L, 201L, null, List.of(), List.of());
    }

    private AuditAnalysisRequest complianceRequest() {
        return new AuditAnalysisRequest("COMPLIANCE_VERIFICATION", 9002L, 202L, null, List.of(), List.of());
    }
}
