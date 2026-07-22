package com.bank.ai.rule.service;

import com.bank.ai.metrics.ReviewMetrics;
import com.bank.ai.review.dto.AutoReviewEvaluateResponse;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.AutoReviewResponse;
import com.bank.ai.review.event.AutoReviewEvaluatedEvent;
import com.bank.ai.review.service.AutoReviewService;
import com.bank.ai.rule.config.RuleEngineProperties;
import com.bank.ai.rule.domain.Track;
import com.bank.ai.rule.domain.TrackDecision;
import com.bank.ai.support.AiErrorCode;
import com.bank.common.web.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 자동심사 종합 entry point — banking-review-llm §3 의 Step 1~6 동기 응답 + pd-label-acquisition §5.3 결합 분기.
 *
 * <p>듀얼 모델 흐름:
 * <ul>
 *   <li>Step 5: 두 ML 모델 추론 (decision: P(APPROVE/REJECT), PD: P(default))
 *               — AutoReviewService 가 한 번에 위임</li>
 *   <li>Step 4: hard constraint 체크 (HardConstraintEvaluator)</li>
 *   <li>Step 6: 듀얼 결합 분기 (TrackClassifier + PolicyMatrix)</li>
 * </ul>
 *
 * <p>PD 모델 미배포·일시 장애 시엔 PD-only 폴백 (decision proba 의 P(REJECT) → pd).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleEngineService {

    /** PD 모델 미가용 시 폴백 — decision proba 의 risk-positive 클래스명. */
    private static final String POSITIVE_CLASS = "REJECT";

    private final AutoReviewService autoReviewService;
    private final TrackClassifier trackClassifier;
    private final RuleEngineProperties props;
    private final ApplicationEventPublisher eventPublisher;
    private final ReviewMetrics reviewMetrics;

    public AutoReviewEvaluateResponse evaluate(AutoReviewRequest req) {
        if (!props.enabled()) {
            log.warn("auto-review disabled — refusing /evaluate request");
            throw new BusinessException(AiErrorCode.AUTO_REVIEW_DISABLED);
        }

        long startNs = System.nanoTime();

        AutoReviewResponse inference;
        try {
            inference = autoReviewService.review(req);
        } catch (BusinessException e) {
            reviewMetrics.recordInferenceError();
            throw e;
        }

        // PD: 우선 PD 모델, 폴백으로 decision 모델의 P(REJECT)
        Double pdFromModel = inference.pdScore();
        double pd = (pdFromModel != null)
                ? pdFromModel
                : inference.proba().getOrDefault(POSITIVE_CLASS, 0.0);

        // decision score: PD 모델 가용 시에만 결합 분기 사용. PD-only 폴백 시 null 전달.
        Double decisionScore = (pdFromModel != null) ? inference.decisionScore() : null;

        TrackDecision decision = trackClassifier.classify(req, pd, decisionScore);

        // 메트릭 기록
        String decisionLabel = trackToDecisionLabel(decision.track());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNs);
        reviewMetrics.recordDecision(decisionLabel, inference.modelVersion());
        reviewMetrics.recordDuration(elapsed, inference.modelVersion());
        reviewMetrics.recordScore(inference.score(), decisionLabel);
        recordInputMetrics(req);

        // Phase 1.6: LLM 비동기 파이프라인 트리거
        eventPublisher.publishEvent(new AutoReviewEvaluatedEvent(req.revId(), req, inference, decision));

        if (props.shadowMode()) {
            log.info("SHADOW run: product={} segment={} track={} (loan-service 가 적재 결정)",
                    req.productCode(), req.applicantSegment(), decision.track());
        }
        return AutoReviewEvaluateResponse.from(inference, decision, props.shadowMode(), "PENDING");
    }

    private void recordInputMetrics(AutoReviewRequest req) {
        if (req.creditScoreProxy() != null) {
            reviewMetrics.recordCreditScore(req.creditScoreProxy());
            if (req.creditScoreProxy() < 0 || req.creditScoreProxy() > 1000) {
                reviewMetrics.recordOutlier("creditScoreProxy");
            }
        } else {
            reviewMetrics.recordMissing("creditScoreProxy");
        }

        if (req.dsr() != null) {
            reviewMetrics.recordDsr(req.dsr());
            if (req.dsr() < 0 || req.dsr() > 1.5) {
                reviewMetrics.recordOutlier("dsr");
            }
        } else {
            reviewMetrics.recordMissing("dsr");
        }
    }

    private static String trackToDecisionLabel(Track track) {
        return switch (track) {
            case TRACK_1 -> "APPROVE";
            case TRACK_2 -> "REJECT";
            default      -> "CONDITIONAL";
        };
    }
}
