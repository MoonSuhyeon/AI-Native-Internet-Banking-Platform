package com.bank.aigateway.prompt;

import com.bank.aigateway.audit.dto.AuditAnalysisRequest;
import com.bank.aigateway.audit.dto.RagChunk;
import com.bank.aigateway.audit.dto.SignalSummary;
import com.bank.aigateway.prompt.compliance.ComplianceVerificationPromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComplianceVerificationPromptBuilderTest {

    ComplianceVerificationPromptBuilder builder;

    @BeforeEach
    void setUp() throws IOException {
        String system = new ClassPathResource("prompts/compliance/system.txt")
                .getContentAsString(StandardCharsets.UTF_8);
        String userTemplate = new ClassPathResource("prompts/compliance/user.txt")
                .getContentAsString(StandardCharsets.UTF_8);
        builder = new ComplianceVerificationPromptBuilder(system, userTemplate);
    }

    @Test
    void 시스템_프롬프트에_JSON_형식_명시() {
        String system = builder.buildSystem();

        assertThat(system).contains("conclusion");
        assertThat(system).contains("VIOLATION_SUSPECTED");
        assertThat(system).contains("COMPLIANT");
        assertThat(system).contains("confidenceScore");
    }

    @Test
    void 유저_프롬프트에_revId_reviewerId_포함() {
        AuditAnalysisRequest req = new AuditAnalysisRequest(
                "COMPLIANCE_VERIFICATION", 9002L, 202L, null, List.of(), List.of());

        String user = builder.buildUser(req);

        assertThat(user).contains("9002");
        assertThat(user).contains("202");
    }

    @Test
    void 신호_있으면_프롬프트에_포함() {
        AuditAnalysisRequest req = new AuditAnalysisRequest(
                "COMPLIANCE_VERIFICATION", 9002L, 202L, null,
                List.of(new SignalSummary("COMPLIANCE_VERIFICATION", "ERROR", "dsr_ratio", 0.91, 0.70)),
                List.of());

        String user = builder.buildUser(req);

        assertThat(user).contains("dsr_ratio");
        assertThat(user).contains("ERROR");
    }

    @Test
    void 신호_없으면_없음_표시() {
        AuditAnalysisRequest req = new AuditAnalysisRequest(
                "COMPLIANCE_VERIFICATION", 9002L, 202L, null, List.of(), List.of());

        String user = builder.buildUser(req);

        assertThat(user).contains("(없음)");
    }

    @Test
    void RAG_청크_있으면_출처와_내용_포함() {
        AuditAnalysisRequest req = new AuditAnalysisRequest(
                "COMPLIANCE_VERIFICATION", 9002L, 202L, null, List.of(),
                List.of(new RagChunk("여신규정집 > 3장", "DSR 70% 초과 시 예외 승인 금지")));

        String user = builder.buildUser(req);

        assertThat(user).contains("여신규정집 > 3장");
        assertThat(user).contains("DSR 70% 초과 시 예외 승인 금지");
    }

    @Test
    void 심사관_의견서_있으면_포함() {
        AuditAnalysisRequest req = new AuditAnalysisRequest(
                "COMPLIANCE_VERIFICATION", 9002L, 202L, "LTV 한도 초과 무시하고 승인", List.of(), List.of());

        String user = builder.buildUser(req);

        assertThat(user).contains("LTV 한도 초과 무시하고 승인");
    }

    @Test
    void 심사관_의견서_null이면_없음_표시() {
        AuditAnalysisRequest req = new AuditAnalysisRequest(
                "COMPLIANCE_VERIFICATION", 9002L, 202L, null, List.of(), List.of());

        String user = builder.buildUser(req);

        assertThat(user).contains("(없음)");
    }
}
