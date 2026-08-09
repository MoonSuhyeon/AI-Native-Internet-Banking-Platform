package com.bank.loan.advisory.dto;

import com.bank.loan.advisory.domain.audit.AiAuditOpinion;

import java.time.OffsetDateTime;

public record AiAuditOpinionResponse(
        Long opinionId,
        Long advrId,
        Long revId,
        Long reviewerId,
        String analysisTypeCd,
        String conclusionCd,
        String reasoningSummary,
        Double confidenceScore,
        Integer inputTokens,
        Integer outputTokens,
        OffsetDateTime generatedAt
) {
    public static AiAuditOpinionResponse from(AiAuditOpinion o) {
        return new AiAuditOpinionResponse(
                o.getOpinionId(), o.getAdvrId(), o.getRevId(), o.getReviewerId(),
                o.getAnalysisTypeCd(), o.getConclusionCd(), o.getReasoningSummary(),
                o.getConfidenceScore(), o.getInputTokens(), o.getOutputTokens(),
                o.getGeneratedAt());
    }
}
