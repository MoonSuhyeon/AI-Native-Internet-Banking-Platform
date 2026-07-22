package com.bank.ai.rule.service;

import com.bank.ai.metrics.ReviewMetrics;
import com.bank.ai.review.dto.AutoReviewResponse;
import com.bank.ai.review.service.AutoReviewService;
import com.bank.ai.rule.TestRequests;
import com.bank.ai.rule.config.RuleEngineProperties;
import com.bank.ai.rule.config.RuleEngineProperties.HardConstraints;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 1.4-PD §5.3 듀얼 score 결합 분기 — PD 모델 + decision 모델 score 동시 가용 시 동작 검증.
 *
 * <pre>
 * τ = 0.40 (MORT_001/regular), safetyTau = 0.40 × 0.30 = 0.12
 * decisionStrongThreshold = 0.95, decisionRejectThreshold = 0.20
 * </pre>
 */
class RuleEngineServiceDualScoreTest {

    private static final RuleEngineProperties PROPS = new RuleEngineProperties(
            new HardConstraints(0.40, 0.70, 600, 0, 19),
            0.30, 0.50,
            Map.of("MORT_001", Map.of("regular", 0.40)),
            0.95, 0.20,
            true, false
    );

    private final AutoReviewService autoReviewService = mock(AutoReviewService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final ReviewMetrics reviewMetrics = mock(ReviewMetrics.class);
    private final TrackClassifier classifier = new TrackClassifier(
            new HardConstraintEvaluator(PROPS), new PolicyMatrix(PROPS)
    );
    private final RuleEngineService service = new RuleEngineService(
            autoReviewService, classifier, PROPS, publisher, reviewMetrics);

    private static AutoReviewResponse dual(double approveProba, double rejectProba, double pdScore) {
        return new AutoReviewResponse(
                "hmda_tx_v1",
                approveProba >= rejectProba ? "APPROVE" : "REJECT",
                Math.max(approveProba, rejectProba),
                Map.of("APPROVE", approveProba, "REJECT", rejectProba),
                pdScore,
                "pd_homecredit_v1_rw"
        );
    }

    @Test
    void 강한_승인_decision_0_97_AND_PD_0_05이면_Track1() {
        // decision 0.97 ≥ 0.95 ∧ PD 0.05 ≤ safetyTau 0.12 → 강한 자동 승인
        when(autoReviewService.review(any())).thenReturn(dual(0.97, 0.03, 0.05));

        var response = service.evaluate(TestRequests.healthy());

        assertThat(response.track()).isEqualTo("TRACK_1");
        assertThat(response.pd()).isEqualTo(0.05);
        assertThat(response.decisionScore()).isEqualTo(0.97);
        assertThat(response.pdModelVersion()).isEqualTo("pd_homecredit_v1_rw");
        assertThat(response.rationale()).contains("강한 자동 승인");
    }

    @Test
    void PD_안전여유는_충족이지만_decision_부족시_Track3() {
        // decision 0.80 < 0.95 (강한 승인 진입 막힘)
        // PD 0.05 < safetyTau 0.12 < threshold 0.40 → 회색지대 Track 3
        when(autoReviewService.review(any())).thenReturn(dual(0.80, 0.20, 0.05));

        var response = service.evaluate(TestRequests.healthy());

        assertThat(response.track()).isEqualTo("TRACK_3");
        assertThat(response.rationale()).contains("회색지대");
    }

    @Test
    void decision_매우_낮으면_PD_매트릭스_미만이어도_Track2() {
        // decision 0.15 ≤ 0.20 (보조 reject)
        // PD 0.30 < threshold 0.40 (PD 만으론 Track 3 이지만 decision 신호로 반려)
        when(autoReviewService.review(any())).thenReturn(dual(0.15, 0.85, 0.30));

        var response = service.evaluate(TestRequests.healthy());

        assertThat(response.track()).isEqualTo("TRACK_2");
        assertThat(response.rationale()).contains("결정신뢰");
        assertThat(response.rationale()).contains("자동 반려");
    }

    @Test
    void PD가_매트릭스_초과면_decision_무관_Track2() {
        // PD 0.55 > threshold 0.40 → 무조건 Track 2 (decision 0.92 무시)
        when(autoReviewService.review(any())).thenReturn(dual(0.92, 0.08, 0.55));

        var response = service.evaluate(TestRequests.healthy());

        assertThat(response.track()).isEqualTo("TRACK_2");
        assertThat(response.rationale()).contains("PD 초과");
    }

    @Test
    void decision_strong_경계값_정확히_0_95에서_Track1() {
        // decision 0.95 == decisionStrongThreshold (boundary)
        when(autoReviewService.review(any())).thenReturn(dual(0.95, 0.05, 0.05));

        var response = service.evaluate(TestRequests.healthy());

        assertThat(response.track()).isEqualTo("TRACK_1");
    }

    @Test
    void decision_reject_경계값_정확히_0_20에서_Track2() {
        // decision 0.20 == decisionRejectThreshold (boundary, ≤ 발동)
        // PD 0.10 < threshold (PD 만으론 Track 1/3) — but decision 컷
        when(autoReviewService.review(any())).thenReturn(dual(0.20, 0.80, 0.10));

        var response = service.evaluate(TestRequests.healthy());

        assertThat(response.track()).isEqualTo("TRACK_2");
        assertThat(response.rationale()).contains("결정신뢰");
    }
}
