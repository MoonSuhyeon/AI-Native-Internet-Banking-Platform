package com.bank.loan.review.metrics;

import com.bank.loan.prescreening.client.AutoReviewEvaluateResult;
import com.bank.loan.review.domain.LoanReview;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AI 권고 채택률 계측 — 심사가 최종 확정되는 시점에 기록한다.
 *
 * <p>기존 AI 메트릭(ai.agent.*)은 전부 시스템 지표(지연·비용·실패율)라
 * "AI 가 잘 도는가"만 답할 수 있고 "사람이 AI 를 받아들였는가"는 답할 수 없었다.
 * 이 클래스가 그 공백을 메운다.
 *
 * <h3>1. AI 권고 채택 — {@code ai.review.adoption.total}</h3>
 * AI 트랙({@code rev_ai_track_cd})은 방향성 있는 권고를 담고 있다.
 * <ul>
 *   <li>{@code TRACK_1} 자동 승인 권고</li>
 *   <li>{@code TRACK_2} 자동 반려 권고</li>
 *   <li>{@code TRACK_3} 사람 심사 필수 — <b>권고가 없으므로 채택률 집계에서 제외</b></li>
 * </ul>
 * 심사역 최종 결정({@code rev_decision_cd})과 방향이 같으면 ADOPTED, 다르면 REJECTED.
 *
 * <h3>2. 4-eye 번복 — {@code review.approver.action.total}</h3>
 * 승인자가 심사역 결정을 그대로 뒀는지(APPROVE_AS_IS) 뒤집었는지(OVERRIDE_*).
 * AI→사람 축과 별개인 사람→사람 축이다.
 *
 * <p>비율 계산은 Prometheus 쪽에서 한다. 예:
 * <pre>
 * sum(rate(ai_review_adoption_total{outcome="ADOPTED"}[1h]))
 *   / sum(rate(ai_review_adoption_total[1h]))
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewAdoptionMetrics {

    private static final String METRIC_ADOPTION = "ai.review.adoption.total";
    private static final String METRIC_APPROVER = "review.approver.action.total";

    private static final String TAG_AI_TRACK = "ai_track";
    private static final String TAG_DECISION = "decision";
    private static final String TAG_OUTCOME  = "outcome";
    private static final String TAG_ACTION   = "action";

    private static final String OUTCOME_ADOPTED     = "ADOPTED";
    private static final String OUTCOME_OVERRIDDEN  = "OVERRIDDEN";

    private final MeterRegistry registry;

    /**
     * 심사 최종 확정 시 호출. AI 권고 채택 여부와 4-eye 동작을 함께 기록한다.
     *
     * @param review            확정된 심사 (revDecisionCd·revAiTrackCd 채워진 상태)
     * @param approverDecisionCd 4-eye 판정 (APPROVE_AS_IS / OVERRIDE_APPROVED / OVERRIDE_REJECTED)
     */
    public void recordFinalDecision(LoanReview review, String approverDecisionCd) {
        recordApproverAction(approverDecisionCd);
        recordAiAdoption(review.getRevAiTrackCd(), review.getRevDecisionCd());
    }

    /** 승인자가 심사역 결정을 유지했는지 번복했는지. */
    private void recordApproverAction(String approverDecisionCd) {
        if (approverDecisionCd == null) {
            return;
        }
        Counter.builder(METRIC_APPROVER)
                .tag(TAG_ACTION, approverDecisionCd)
                .description("4-eye 승인자 판정 분포 (AS_IS 유지 / OVERRIDE 번복)")
                .register(registry)
                .increment();
    }

    /**
     * AI 권고와 사람 최종 결정의 일치 여부.
     *
     * <p>TRACK_3(사람 심사 필수)은 AI 가 방향을 제시하지 않았으므로 집계하지 않는다.
     * 여기서 세면 "사람이 알아서 정한 건"이 채택/기각으로 잘못 계산된다.
     */
    private void recordAiAdoption(String aiTrackCd, String decisionCd) {
        if (aiTrackCd == null || decisionCd == null) {
            return;
        }

        String recommended = recommendationOf(aiTrackCd);
        if (recommended == null) {
            return; // TRACK_3 또는 미지의 트랙 — 권고 없음
        }

        String outcome = recommended.equals(decisionCd) ? OUTCOME_ADOPTED : OUTCOME_OVERRIDDEN;

        Counter.builder(METRIC_ADOPTION)
                .tag(TAG_AI_TRACK, aiTrackCd)
                .tag(TAG_DECISION, decisionCd)
                .tag(TAG_OUTCOME, outcome)
                .description("AI 트랙 권고 대비 심사역 최종 결정 일치 여부 (TRACK_3 제외)")
                .register(registry)
                .increment();

        if (OUTCOME_OVERRIDDEN.equals(outcome)) {
            log.info("[adoption] AI 권고 미채택 track={} decision={}", aiTrackCd, decisionCd);
        }
    }

    /** AI 트랙을 사람 결정 코드와 비교 가능한 형태로 환산. 권고 없음이면 null. */
    private String recommendationOf(String aiTrackCd) {
        return switch (aiTrackCd) {
            case AutoReviewEvaluateResult.TRACK_1 -> LoanReview.DECISION_APPROVED;
            case AutoReviewEvaluateResult.TRACK_2 -> LoanReview.DECISION_REJECTED;
            default -> null;   // TRACK_3 = 사람 심사 필수
        };
    }
}
