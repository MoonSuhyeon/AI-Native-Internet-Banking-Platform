package com.bank.loan.advisory.domain.audit;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * 심사관별 누적 위험도 스코어. 감사 분석 결과가 나올 때마다 갱신 (UPSERT).
 * bias_score + compliance_score 각 0~100, 높을수록 위험.
 */
@Getter
@Entity
@Table(name = "reviewer_risk_score")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReviewerRiskScore {

    private static final double BIAS_SUSPECTED_DELTA       =  5.0;
    private static final double VIOLATION_SUSPECTED_DELTA  = 10.0;
    private static final double CLEAN_DELTA                = -1.0;
    private static final double MAX_SCORE                  = 100.0;
    private static final double MIN_SCORE                  =   0.0;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "score_id")
    private Long scoreId;

    @Column(name = "reviewer_id", nullable = false, unique = true)
    private Long reviewerId;

    @Column(name = "bias_score", nullable = false)
    private double biasScore;

    @Column(name = "compliance_score", nullable = false)
    private double complianceScore;

    @Column(name = "evaluation_count", nullable = false)
    private int evaluationCount;

    @Column(name = "last_evaluated_at", nullable = false)
    private OffsetDateTime lastEvaluatedAt;

    public static ReviewerRiskScore init(Long reviewerId) {
        return ReviewerRiskScore.builder()
                .reviewerId(reviewerId)
                .biasScore(0.0)
                .complianceScore(0.0)
                .evaluationCount(0)
                .lastEvaluatedAt(OffsetDateTime.now())
                .build();
    }

    public void applyBiasConclusion(String conclusionCd) {
        double delta = AiAuditOpinion.CONCLUSION_BIAS_SUSPECTED.equals(conclusionCd)
                ? BIAS_SUSPECTED_DELTA : CLEAN_DELTA;
        biasScore = clamp(biasScore + delta);
        evaluationCount++;
        lastEvaluatedAt = OffsetDateTime.now();
    }

    public void applyComplianceConclusion(String conclusionCd) {
        double delta = AiAuditOpinion.CONCLUSION_VIOLATION_SUSPECTED.equals(conclusionCd)
                ? VIOLATION_SUSPECTED_DELTA : CLEAN_DELTA;
        complianceScore = clamp(complianceScore + delta);
        evaluationCount++;
        lastEvaluatedAt = OffsetDateTime.now();
    }

    private static double clamp(double value) {
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, value));
    }
}
