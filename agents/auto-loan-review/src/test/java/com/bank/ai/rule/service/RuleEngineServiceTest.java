package com.bank.ai.rule.service;

import com.bank.ai.metrics.ReviewMetrics;
import com.bank.ai.review.dto.AutoReviewResponse;
import com.bank.ai.review.service.AutoReviewService;
import com.bank.ai.rule.TestRequests;
import com.bank.ai.rule.config.RuleEngineProperties;
import com.bank.ai.rule.config.RuleEngineProperties.HardConstraints;
import com.bank.ai.support.AiErrorCode;
import com.bank.common.web.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AutoReviewService 를 mock 으로 두고 RuleEngineService → TrackClassifier PD-only 폴백 흐름 검증.
 * 듀얼 score 결합 분기는 별도 {@link RuleEngineServiceDualScoreTest} 참조.
 */
class RuleEngineServiceTest {

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
    private final RuleEngineService service = new RuleEngineService(autoReviewService, classifier, PROPS, publisher, reviewMetrics);

    /** PD 모델 미배포 환경 — decision-only 폴백 시 사용. */
    private static AutoReviewResponse decisionOnly(String decision, double score, Map<String, Double> proba) {
        return new AutoReviewResponse("hmda_tx_v1", decision, score, proba, null, null);
    }

    @Test
    void PD_모델_미배포_시_decision_REJECT_확률이_PD로_사용된다() {
        when(autoReviewService.review(any())).thenReturn(decisionOnly(
                "APPROVE", 0.92, Map.of("APPROVE", 0.92, "REJECT", 0.08)
        ));

        var response = service.evaluate(TestRequests.healthy());

        assertThat(response.pd()).isEqualTo(0.08);
        assertThat(response.modelVersion()).isEqualTo("hmda_tx_v1");
        assertThat(response.track()).isEqualTo("TRACK_1");  // 0.08 ≤ 0.12 safetyTau
        assertThat(response.trackDisplayName()).isEqualTo("자동 승인 권고");
        assertThat(response.proba()).containsEntry("APPROVE", 0.92);
    }

    @Test
    void REJECT_확률_누락시_PD_0으로_안전처리() {
        when(autoReviewService.review(any())).thenReturn(decisionOnly(
                "APPROVE", 1.0, Map.of("APPROVE", 1.0)
        ));

        var response = service.evaluate(TestRequests.healthy());

        assertThat(response.pd()).isEqualTo(0.0);
        assertThat(response.track()).isEqualTo("TRACK_1");
    }

    @Test
    void hard_fail은_inference_결과보다_우선해_Track2() {
        when(autoReviewService.review(any())).thenReturn(decisionOnly(
                "APPROVE", 0.98, Map.of("APPROVE", 0.98, "REJECT", 0.02)
        ));
        // DSR 위반된 신청자 — PD 가 매우 낮아도 Track 2
        var req = TestRequests.baseline(0.50, 0.50, 750, 0, 35, "MORT_001", "regular");

        var response = service.evaluate(req);

        assertThat(response.track()).isEqualTo("TRACK_2");
        assertThat(response.hardFailCodes()).contains("DSR_EXCEEDED");
        assertThat(response.hardFailMessages()).anyMatch(m -> m.contains("DSR"));
    }

    @Test
    void enabled_false면_AUTO_REVIEW_DISABLED_throw_inference호출_X() {
        var disabledProps = new RuleEngineProperties(
                new HardConstraints(0.40, 0.70, 600, 0, 19),
                0.30, 0.50, Map.of("MORT_001", Map.of("regular", 0.40)),
                0.95, 0.20,
                false,  // enabled = OFF (kill switch 작동)
                false
        );
        var disabledService = new RuleEngineService(autoReviewService, classifier, disabledProps, publisher, reviewMetrics);

        assertThatThrownBy(() -> disabledService.evaluate(TestRequests.healthy()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(AiErrorCode.AUTO_REVIEW_DISABLED);

        verify(autoReviewService, never()).review(any());
    }

    @Test
    void shadowMode_true면_응답에_shadow_true_정상결과는_그대로() {
        var shadowProps = new RuleEngineProperties(
                new HardConstraints(0.40, 0.70, 600, 0, 19),
                0.30, 0.50, Map.of("MORT_001", Map.of("regular", 0.40)),
                0.95, 0.20,
                true,
                true   // shadow = ON
        );
        var shadowService = new RuleEngineService(autoReviewService, classifier, shadowProps, publisher, reviewMetrics);
        when(autoReviewService.review(any())).thenReturn(decisionOnly(
                "APPROVE", 0.92, Map.of("APPROVE", 0.92, "REJECT", 0.08)
        ));

        var response = shadowService.evaluate(TestRequests.healthy());

        assertThat(response.shadow()).isTrue();
        assertThat(response.track()).isEqualTo("TRACK_1");   // 추론·분기 모두 정상 진행
        assertThat(response.pd()).isEqualTo(0.08);
    }

    @Test
    void shadowMode_false_기본은_응답_shadow_false() {
        when(autoReviewService.review(any())).thenReturn(decisionOnly(
                "APPROVE", 0.92, Map.of("APPROVE", 0.92, "REJECT", 0.08)
        ));

        var response = service.evaluate(TestRequests.healthy());

        assertThat(response.shadow()).isFalse();
    }
}
