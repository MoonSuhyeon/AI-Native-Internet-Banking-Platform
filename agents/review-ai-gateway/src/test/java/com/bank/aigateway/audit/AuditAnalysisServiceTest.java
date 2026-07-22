package com.bank.aigateway.audit;

import com.bank.aigateway.audit.dto.AuditAnalysisRequest;
import com.bank.aigateway.audit.dto.AuditAnalysisResponse;
import com.bank.aigateway.audit.dto.RagChunk;
import com.bank.aigateway.audit.dto.SignalSummary;
import com.bank.aigateway.llm.LlmClient;
import com.bank.aigateway.llm.LlmRequest;
import com.bank.aigateway.llm.LlmResponse;
import com.bank.aigateway.observability.GatewayMetrics;
import com.bank.aigateway.parser.AuditResponseParser;
import com.bank.aigateway.prompt.bias.BiasDetectionPromptBuilder;
import com.bank.aigateway.prompt.compliance.ComplianceVerificationPromptBuilder;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuditAnalysisServiceTest {

    LlmClient                        llmClient          = mock(LlmClient.class);
    BiasDetectionPromptBuilder       biasBuilder        = mock(BiasDetectionPromptBuilder.class);
    ComplianceVerificationPromptBuilder complianceBuilder = mock(ComplianceVerificationPromptBuilder.class);
    AuditResponseParser              parser             = mock(AuditResponseParser.class);
    GatewayMetrics                   metrics            = mock(GatewayMetrics.class);

    AuditAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new AuditAnalysisService(llmClient, biasBuilder, complianceBuilder, parser, metrics);
        when(metrics.startAnalysisTimer()).thenReturn(mock(Timer.Sample.class));
        when(biasBuilder.buildSystem()).thenReturn("bias-system");
        when(biasBuilder.buildUser(any())).thenReturn("bias-user");
        when(complianceBuilder.buildSystem()).thenReturn("compliance-system");
        when(complianceBuilder.buildUser(any())).thenReturn("compliance-user");
    }

    @Test
    void BIAS_DETECTION_요청은_biasBuilder_사용() {
        AuditAnalysisRequest req = biasRequest();
        LlmResponse llmResp = new LlmResponse("{}", 100, 80);
        AuditResponseParser.ParsedAuditResult parsed =
                new AuditResponseParser.ParsedAuditResult("BIAS_SUSPECTED", "편향 의심", 0.85, List.of());
        when(llmClient.complete(any())).thenReturn(llmResp);
        when(parser.parse(any())).thenReturn(parsed);

        AuditAnalysisResponse resp = service.analyze(req);

        verify(biasBuilder).buildSystem();
        verify(biasBuilder).buildUser(req);
        verifyNoInteractions(complianceBuilder);
        assertThat(resp.analysisType()).isEqualTo("BIAS_DETECTION");
        assertThat(resp.conclusion()).isEqualTo("BIAS_SUSPECTED");
    }

    @Test
    void COMPLIANCE_VERIFICATION_요청은_complianceBuilder_사용() {
        AuditAnalysisRequest req = complianceRequest();
        LlmResponse llmResp = new LlmResponse("{}", 120, 90);
        AuditResponseParser.ParsedAuditResult parsed =
                new AuditResponseParser.ParsedAuditResult("COMPLIANT", "규정 준수", 0.93, List.of());
        when(llmClient.complete(any())).thenReturn(llmResp);
        when(parser.parse(any())).thenReturn(parsed);

        AuditAnalysisResponse resp = service.analyze(req);

        verify(complianceBuilder).buildSystem();
        verify(complianceBuilder).buildUser(req);
        verifyNoInteractions(biasBuilder);
        assertThat(resp.analysisType()).isEqualTo("COMPLIANCE_VERIFICATION");
        assertThat(resp.conclusion()).isEqualTo("COMPLIANT");
    }

    @Test
    void LLM_응답_토큰_메트릭_기록() {
        AuditAnalysisRequest req = biasRequest();
        when(llmClient.complete(any())).thenReturn(new LlmResponse("{}", 200, 150));
        when(parser.parse(any())).thenReturn(new AuditResponseParser.ParsedAuditResult("NO_BIAS_DETECTED", "", 0.9, List.of()));

        service.analyze(req);

        verify(metrics).recordTokens("BIAS_DETECTION", 200, 150);
        verify(metrics).recordAnalysisDuration(any(), eq("BIAS_DETECTION"));
    }

    @Test
    void 응답_confidenceScore_매핑() {
        AuditAnalysisRequest req = biasRequest();
        when(llmClient.complete(any())).thenReturn(new LlmResponse("{}", 100, 80));
        when(parser.parse(any())).thenReturn(
                new AuditResponseParser.ParsedAuditResult("BIAS_SUSPECTED", "근거 요약", 0.77, List.of()));

        AuditAnalysisResponse resp = service.analyze(req);

        assertThat(resp.confidenceScore()).isEqualTo(0.77);
        assertThat(resp.reasoningSummary()).isEqualTo("근거 요약");
        assertThat(resp.inputTokens()).isEqualTo(100);
        assertThat(resp.outputTokens()).isEqualTo(80);
    }

    @Test
    void 미지원_analysisType은_IllegalArgumentException() {
        AuditAnalysisRequest req = new AuditAnalysisRequest(
                "UNKNOWN_TYPE", 1L, 1L, null, List.of(), List.of());

        assertThatThrownBy(() -> service.analyze(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN_TYPE");
    }

    @Test
    void LLM_프롬프트에_revId_reviewerId_전달() {
        AuditAnalysisRequest req = new AuditAnalysisRequest(
                "BIAS_DETECTION", 9001L, 201L, "심사 의견 텍스트",
                List.of(new SignalSummary("BIAS_DETECTION", "WARN", "approve_rate", 0.62, 0.80)),
                List.of(new RagChunk("HMDA 기준서", "편향 판단 기준: DIR < 0.80"))
        );
        when(llmClient.complete(any())).thenReturn(new LlmResponse("{}", 300, 100));
        when(parser.parse(any())).thenReturn(
                new AuditResponseParser.ParsedAuditResult("BIAS_SUSPECTED", "", 0.8, List.of()));

        service.analyze(req);

        ArgumentCaptor<AuditAnalysisRequest> reqCaptor = ArgumentCaptor.forClass(AuditAnalysisRequest.class);
        verify(biasBuilder).buildUser(reqCaptor.capture());
        assertThat(reqCaptor.getValue().revId()).isEqualTo(9001L);
        assertThat(reqCaptor.getValue().reviewerId()).isEqualTo(201L);
        assertThat(reqCaptor.getValue().signals()).hasSize(1);
        assertThat(reqCaptor.getValue().ragChunks()).hasSize(1);
    }

    // ── helpers ──────────────────────────────────────────────────

    private AuditAnalysisRequest biasRequest() {
        return new AuditAnalysisRequest("BIAS_DETECTION", 9001L, 201L, null, List.of(), List.of());
    }

    private AuditAnalysisRequest complianceRequest() {
        return new AuditAnalysisRequest("COMPLIANCE_VERIFICATION", 9002L, 202L, null, List.of(), List.of());
    }
}
