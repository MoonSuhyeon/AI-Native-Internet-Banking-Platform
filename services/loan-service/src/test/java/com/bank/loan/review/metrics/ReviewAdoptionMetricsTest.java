package com.bank.loan.review.metrics;

import com.bank.loan.review.domain.LoanReview;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 권고 채택률 계측 단위 테스트.
 *
 * <p>핵심 규약 3가지를 고정한다.
 * <ol>
 *   <li>TRACK_1=승인권고 / TRACK_2=반려권고 방향이 사람 결정과 같으면 ADOPTED</li>
 *   <li>TRACK_3(사람 심사 필수)은 <b>집계에서 제외</b> — 권고가 없으므로</li>
 *   <li>4-eye 판정은 AI 축과 무관하게 별도 카운터로 기록</li>
 * </ol>
 */
class ReviewAdoptionMetricsTest {

    private MeterRegistry registry;
    private ReviewAdoptionMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new ReviewAdoptionMetrics(registry);
    }

    private LoanReview review(String aiTrackCd, String decisionCd) {
        return LoanReview.builder()
                .revId(1L)
                .revAiTrackCd(aiTrackCd)
                .revDecisionCd(decisionCd)
                .build();
    }

    private double adoptionCount(String outcome) {
        return registry.find("ai.review.adoption.total")
                .tag("outcome", outcome)
                .counters().stream()
                .mapToDouble(c -> c.count())
                .sum();
    }

    @Test
    @DisplayName("TRACK_1 승인권고 → 심사역도 승인이면 ADOPTED")
    void track1Approved_isAdopted() {
        metrics.recordFinalDecision(review("TRACK_1", LoanReview.DECISION_APPROVED), "APPROVE_AS_IS");

        assertThat(adoptionCount("ADOPTED")).isEqualTo(1.0);
        assertThat(adoptionCount("OVERRIDDEN")).isZero();
    }

    @Test
    @DisplayName("TRACK_1 승인권고 → 심사역이 반려하면 OVERRIDDEN")
    void track1Rejected_isOverridden() {
        metrics.recordFinalDecision(review("TRACK_1", LoanReview.DECISION_REJECTED), "OVERRIDE_REJECTED");

        assertThat(adoptionCount("OVERRIDDEN")).isEqualTo(1.0);
        assertThat(adoptionCount("ADOPTED")).isZero();
    }

    @Test
    @DisplayName("TRACK_2 반려권고 → 심사역도 반려면 ADOPTED")
    void track2Rejected_isAdopted() {
        metrics.recordFinalDecision(review("TRACK_2", LoanReview.DECISION_REJECTED), "APPROVE_AS_IS");

        assertThat(adoptionCount("ADOPTED")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("TRACK_2 반려권고 → 심사역이 승인하면 OVERRIDDEN")
    void track2Approved_isOverridden() {
        metrics.recordFinalDecision(review("TRACK_2", LoanReview.DECISION_APPROVED), "OVERRIDE_APPROVED");

        assertThat(adoptionCount("OVERRIDDEN")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("TRACK_3 은 권고가 없으므로 채택률 집계에서 제외된다")
    void track3_isExcluded() {
        metrics.recordFinalDecision(review("TRACK_3", LoanReview.DECISION_APPROVED), "APPROVE_AS_IS");
        metrics.recordFinalDecision(review("TRACK_3", LoanReview.DECISION_REJECTED), "APPROVE_AS_IS");

        assertThat(registry.find("ai.review.adoption.total").counters()).isEmpty();
        // 4-eye 카운터는 트랙과 무관하게 기록돼야 한다
        assertThat(registry.find("review.approver.action.total").counter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("AI 트랙이 없는 심사(에이전트 미실행)는 집계하지 않는다")
    void nullTrack_isSkipped() {
        metrics.recordFinalDecision(review(null, LoanReview.DECISION_APPROVED), "APPROVE_AS_IS");

        assertThat(registry.find("ai.review.adoption.total").counters()).isEmpty();
    }

    @Test
    @DisplayName("4-eye 판정은 action 태그로 분리 집계된다")
    void approverAction_taggedSeparately() {
        metrics.recordFinalDecision(review("TRACK_1", LoanReview.DECISION_APPROVED), "APPROVE_AS_IS");
        metrics.recordFinalDecision(review("TRACK_1", LoanReview.DECISION_REJECTED), "OVERRIDE_REJECTED");

        assertThat(registry.find("review.approver.action.total")
                .tag("action", "APPROVE_AS_IS").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("review.approver.action.total")
                .tag("action", "OVERRIDE_REJECTED").counter().count()).isEqualTo(1.0);
    }
}
