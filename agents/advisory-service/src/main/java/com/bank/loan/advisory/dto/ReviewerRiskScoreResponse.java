package com.bank.loan.advisory.dto;

import com.bank.loan.advisory.domain.audit.ReviewerRiskScore;

import java.time.OffsetDateTime;

public record ReviewerRiskScoreResponse(
        Long reviewerId,
        double biasScore,
        double complianceScore,
        int evaluationCount,
        OffsetDateTime lastEvaluatedAt
) {
    public static ReviewerRiskScoreResponse from(ReviewerRiskScore s) {
        return new ReviewerRiskScoreResponse(
                s.getReviewerId(), s.getBiasScore(), s.getComplianceScore(),
                s.getEvaluationCount(), s.getLastEvaluatedAt());
    }
}
