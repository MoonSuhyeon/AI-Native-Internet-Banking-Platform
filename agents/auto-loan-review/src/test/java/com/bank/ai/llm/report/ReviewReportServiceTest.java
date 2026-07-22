package com.bank.ai.llm.report;

import com.bank.ai.llm.client.LlmCallException;
import com.bank.ai.llm.client.LlmClient;
import com.bank.ai.llm.client.LlmRequest;
import com.bank.ai.llm.client.StubLlmClient;
import com.bank.ai.llm.config.LlmProperties;
import com.bank.ai.llm.policy.InlinePolicyIndex;
import com.bank.ai.llm.policy.PolicyIndex;
import com.bank.ai.llm.prompt.PromptRegistry;
import com.bank.ai.llm.support.LlmCostMeter;
import com.bank.ai.rule.domain.HardFailReason;
import com.bank.ai.rule.domain.Track;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewReportServiceTest {

    private static final LlmProperties ENABLED = new LlmProperties(
            true, LlmProperties.Provider.STUB, "stub-v1", 1024, 0.0, 1_000_000L, "", "", 0, 0
    );
    private static final LlmProperties DISABLED = new LlmProperties(
            false, LlmProperties.Provider.STUB, "stub-v1", 1024, 0.0, 1_000_000L, "", "", 0, 0
    );

    /** 인라인 정책 — 모든 stub citation id 와 hard fail 매핑 포함. */
    private static final InlinePolicyIndex POLICY = new InlinePolicyIndex(Map.ofEntries(
            Map.entry("PD_THRESHOLD_MATRIX_V1",
                    new PolicyIndex.PolicyEntry("PD 매트릭스", "internal_policy_2026q2")),
            Map.entry("AUTO_REVIEW_GOVERNANCE_V1",
                    new PolicyIndex.PolicyEntry("자동심사 거버넌스", "internal_policy_2026q2")),
            Map.entry("MORT_DSR_LIMIT_V1",
                    new PolicyIndex.PolicyEntry("DSR 한도", "internal_policy_2026q2")),
            Map.entry("MORT_LTV_LIMIT_V1",
                    new PolicyIndex.PolicyEntry("LTV 한도", "internal_policy_2026q2")),
            Map.entry("CRED_SCORE_MIN_V1",
                    new PolicyIndex.PolicyEntry("신용점수 최저", "internal_policy_2026q2")),
            Map.entry("DELINQ_24M_BAR_V1",
                    new PolicyIndex.PolicyEntry("연체 24m", "internal_policy_2026q2")),
            Map.entry("AGE_MIN_V1",
                    new PolicyIndex.PolicyEntry("연령 최저", "civil_law_§4"))
    ));

    private final GroundingValidator validator = new GroundingValidator(POLICY, java.util.Optional.empty(),
            mock(com.bank.ai.metrics.AgentMetricsRecorder.class));
    private final TemplateFallback fallback = new TemplateFallback(POLICY);
    private final StubLlmClient stub = new StubLlmClient(new ObjectMapper());
    private final PromptRegistry registry = loadRegistry();
    private final LlmCostMeter costMeter = buildCostMeter(ENABLED);

    /** 실제 YAML 파일을 classpath 에서 로드 — 파일-코드 정합성까지 검증. */
    private static PromptRegistry loadRegistry() {
        var r = new PromptRegistry();
        try {
            r.load();
        } catch (IOException e) {
            throw new RuntimeException("테스트 PromptRegistry 로드 실패", e);
        }
        return r;
    }

    private static LlmCostMeter buildCostMeter(LlmProperties props) {
        var m = new LlmCostMeter(new SimpleMeterRegistry(), props);
        m.registerGauge();
        return m;
    }

    private ReviewReportInput input(Track track, double pd, List<HardFailReason> hardFails) {
        return new ReviewReportInput(
                track, pd, 0.92, 0.40, 0.12,
                hardFails,
                "regular / professional / Q4 / 35-44",
                "MORT_001",
                null,
                List.of()
        );
    }

    @Test
    void Track1_stub_정상_응답_summary_strengths_채워짐() {
        var svc = new ReviewReportService(stub, validator, fallback, ENABLED, registry, costMeter);

        var r = svc.generate(input(Track.TRACK_1, 0.05, List.of()));

        assertThat(r.track()).isEqualTo(Track.TRACK_1);
        assertThat(r.summary()).contains("자동 승인");
        assertThat(r.strengths()).isNotEmpty();
        assertThat(r.citations()).isNotEmpty();
        assertThat(r.isFallback()).isFalse();
    }

    @Test
    void Track2_stub_응답은_citation_2개_이상_grounding_통과() {
        var svc = new ReviewReportService(stub, validator, fallback, ENABLED, registry, costMeter);

        var r = svc.generate(input(Track.TRACK_2, 0.55,
                List.of(HardFailReason.DSR_EXCEEDED)));

        assertThat(r.track()).isEqualTo(Track.TRACK_2);
        assertThat(r.citations().size()).isGreaterThanOrEqualTo(2);
        assertThat(r.riskFactors()).isNotEmpty();
        assertThat(r.isFallback()).isFalse();
    }

    @Test
    void Track3_stub_응답은_riskFactors_strengths_모두() {
        var svc = new ReviewReportService(stub, validator, fallback, ENABLED, registry, costMeter);

        var r = svc.generate(input(Track.TRACK_3, 0.25, List.of()));

        assertThat(r.track()).isEqualTo(Track.TRACK_3);
        assertThat(r.riskFactors()).isNotEmpty();
        assertThat(r.strengths()).isNotEmpty();
        assertThat(r.summary())
                .contains("위험요인")
                .contains("강점")
                .contains("권고");
    }

    @Test
    void enabled_false면_LLM_호출없이_fallback() {
        var llm = mock(LlmClient.class);
        var svc = new ReviewReportService(llm, validator, fallback, DISABLED, registry, buildCostMeter(DISABLED));

        var r = svc.generate(input(Track.TRACK_1, 0.05, List.of()));

        assertThat(r.isFallback()).isTrue();
        assertThat(r.fallbackReason()).contains("비활성화");
        verify(llm, never()).call(any(), any());
    }

    @Test
    void LLM_예외시_fallback_으로_우회() {
        var llm = mock(LlmClient.class);
        when(llm.call(any(LlmRequest.class), eq(ReviewReport.class)))
                .thenThrow(new LlmCallException("provider timeout"));
        var svc = new ReviewReportService(llm, validator, fallback, ENABLED, registry, costMeter);

        var r = svc.generate(input(Track.TRACK_2, 0.55,
                List.of(HardFailReason.LTV_EXCEEDED)));

        assertThat(r.isFallback()).isTrue();
        assertThat(r.fallbackReason()).contains("LLM 호출 실패");
        // fallback 도 Track 2 의 ≥ 2 인용 강제 통과
        assertThat(r.citations().size()).isGreaterThanOrEqualTo(2);
        assertThat(validator.validate(r).passed()).isTrue();
    }

    @Test
    void LLM_응답_track_불일치시_fallback() {
        var llm = mock(LlmClient.class);
        // 요청은 TRACK_1, 응답은 TRACK_2 ← LLM 자체 분기 시도
        var rogue = new ReviewReport(
                Track.TRACK_2, "응답이 다른 track 으로",
                List.of(), List.of(),
                "권고",
                List.of(new ReviewReport.Citation("PD_THRESHOLD_MATRIX_V1", "src", "text")),
                null
        );
        when(llm.call(any(LlmRequest.class), eq(ReviewReport.class))).thenReturn(rogue);
        var svc = new ReviewReportService(llm, validator, fallback, ENABLED, registry, costMeter);

        var r = svc.generate(input(Track.TRACK_1, 0.05, List.of()));

        assertThat(r.isFallback()).isTrue();
        assertThat(r.fallbackReason()).contains("track 불일치");
        assertThat(r.track()).isEqualTo(Track.TRACK_1);
    }

    @Test
    void LLM_환각_citation_시_grounding_fail_로_fallback() {
        var llm = mock(LlmClient.class);
        var halluc = new ReviewReport(
                Track.TRACK_1, "환각",
                List.of(), List.of(),
                "권고",
                List.of(new ReviewReport.Citation("FAKE_POLICY", "src", "없는 정책")),
                null
        );
        when(llm.call(any(LlmRequest.class), eq(ReviewReport.class))).thenReturn(halluc);
        var svc = new ReviewReportService(llm, validator, fallback, ENABLED, registry, costMeter);

        var r = svc.generate(input(Track.TRACK_1, 0.05, List.of()));

        assertThat(r.isFallback()).isTrue();
        assertThat(r.fallbackReason()).contains("grounding 실패");
    }

    @Test
    void Track2_LLM_인용_부족시_fallback() {
        var llm = mock(LlmClient.class);
        var insufficient = new ReviewReport(
                Track.TRACK_2, "부족",
                List.of(new ReviewReport.RiskFactor("X", "위험", 1.0, "PD_THRESHOLD_MATRIX_V1")),
                List.of(),
                "권고",
                // 1개만 — Track 2 는 ≥ 2 강제
                List.of(new ReviewReport.Citation("PD_THRESHOLD_MATRIX_V1", "src", "정책")),
                null
        );
        when(llm.call(any(LlmRequest.class), eq(ReviewReport.class))).thenReturn(insufficient);
        var svc = new ReviewReportService(llm, validator, fallback, ENABLED, registry, costMeter);

        var r = svc.generate(input(Track.TRACK_2, 0.60, List.of()));

        assertThat(r.isFallback()).isTrue();
        assertThat(r.fallbackReason()).contains("Track 2 인용 부족");
    }

    @Test
    void fallback_은_GroundingValidator_를_항상_통과한다() {
        // hardFail 별로 fallback 산출 → validate
        var svc = new ReviewReportService(
                mock(LlmClient.class), validator, fallback, DISABLED, registry, buildCostMeter(DISABLED));  // LLM 비활성 → 무조건 fallback

        for (var hf : HardFailReason.values()) {
            var r = svc.generate(input(Track.TRACK_2, 0.60, List.of(hf)));
            assertThat(r.isFallback()).isTrue();
            var validation = validator.validate(r);
            assertThat(validation.passed())
                    .as("fallback Track 2 for %s should be grounding-valid", hf)
                    .isTrue();
        }
    }

    @Test
    void prompt_id는_track_별_분기된다() {
        // plan/llm-pipeline.md §5.3 — review_report_track{N} (언더스코어 없음)
        assertThat(ReviewReportService.trackPromptId(Track.TRACK_1))
                .isEqualTo("review_report_track1");
        assertThat(ReviewReportService.trackPromptId(Track.TRACK_2))
                .isEqualTo("review_report_track2");
        assertThat(ReviewReportService.trackPromptId(Track.TRACK_3))
                .isEqualTo("review_report_track3");
    }
}
