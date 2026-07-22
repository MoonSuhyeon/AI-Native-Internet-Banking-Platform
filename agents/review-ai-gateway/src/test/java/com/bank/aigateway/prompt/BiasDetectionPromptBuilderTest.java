package com.bank.aigateway.prompt;

import com.bank.aigateway.audit.dto.AuditAnalysisRequest;
import com.bank.aigateway.audit.dto.RagChunk;
import com.bank.aigateway.audit.dto.SignalSummary;
import com.bank.aigateway.prompt.bias.BiasDetectionPromptBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BiasDetectionPromptBuilderTest {

    BiasDetectionPromptBuilder builder;

    @BeforeEach
    void setUp() throws IOException {
        String system = new ClassPathResource("prompts/bias/system.txt")
                .getContentAsString(StandardCharsets.UTF_8);
        String userTemplate = new ClassPathResource("prompts/bias/user.txt")
                .getContentAsString(StandardCharsets.UTF_8);
        builder = new BiasDetectionPromptBuilder(system, userTemplate);
    }

    @Test
    void 시스템_프롬프트에_JSON_형식_명시() {
        String system = builder.buildSystem();

        assertThat(system).contains("conclusion");
        assertThat(system).contains("BIAS_SUSPECTED");
        assertThat(system).contains("NO_BIAS_DETECTED");
        assertThat(system).contains("confidenceScore");
    }

    @Test
    void 유저_프롬프트에_revId_reviewerId_포함() {
        AuditAnalysisRequest req = new AuditAnalysisRequest(
                "BIAS_DETECTION", 9001L, 201L, null, List.of(), List.of());

        String user = builder.buildUser(req);

        assertThat(user).contains("9001");
        assertThat(user).contains("201");
    }

    @Test
    void 신호_있으면_프롬프트에_포함() {
        AuditAnalysisRequest req = new AuditAnalysisRequest(
                "BIAS_DETECTION", 9001L, 201L, null,
                List.of(new SignalSummary("BIAS_DETECTION", "WARN", "approve_rate_bps", 6200.0, 8000.0)),
                List.of());

        String user = builder.buildUser(req);

        assertThat(user).contains("approve_rate_bps");
        assertThat(user).contains("WARN");
    }

    @Test
    void 신호_없으면_없음_표시() {
        AuditAnalysisRequest req = new AuditAnalysisRequest(
                "BIAS_DETECTION", 9001L, 201L, null, List.of(), List.of());

        String user = builder.buildUser(req);

        assertThat(user).contains("(없음)");
    }

    @Test
    void RAG_청크_있으면_출처와_내용_포함() {
        AuditAnalysisRequest req = new AuditAnalysisRequest(
                "BIAS_DETECTION", 9001L, 201L, null, List.of(),
                List.of(new RagChunk("HMDA 기준서 > 2절", "DIR < 0.80 시 편향 의심")));

        String user = builder.buildUser(req);

        assertThat(user).contains("HMDA 기준서 > 2절");
        assertThat(user).contains("DIR < 0.80 시 편향 의심");
    }

    @Test
    void 심사관_의견서_있으면_포함() {
        AuditAnalysisRequest req = new AuditAnalysisRequest(
                "BIAS_DETECTION", 9001L, 201L, "주관적 의견으로 거절", List.of(), List.of());

        String user = builder.buildUser(req);

        assertThat(user).contains("주관적 의견으로 거절");
    }

    @Test
    void 심사관_의견서_null이면_없음_표시() {
        AuditAnalysisRequest req = new AuditAnalysisRequest(
                "BIAS_DETECTION", 9001L, 201L, null, List.of(), List.of());

        String user = builder.buildUser(req);

        assertThat(user).contains("(없음)");
    }
}
