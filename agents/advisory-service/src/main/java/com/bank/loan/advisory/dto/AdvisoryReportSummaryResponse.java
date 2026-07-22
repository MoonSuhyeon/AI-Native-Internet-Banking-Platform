package com.bank.loan.advisory.dto;

import com.bank.loan.advisory.domain.ReviewAdvisoryReport;

import java.time.OffsetDateTime;

/** 리포트 목록 항목. */
public record AdvisoryReportSummaryResponse(
        Long advrId,
        Long revId,
        Long ruleId,
        String advisoryTypeCd,
        String severityCd,
        String advrStatusCd,
        String advrTitle,
        Long targetReviewerId,
        OffsetDateTime generatedAt,
        OffsetDateTime firstViewedAt,
        OffsetDateTime resolvedAt
) {
    public static AdvisoryReportSummaryResponse of(ReviewAdvisoryReport r) {
        return new AdvisoryReportSummaryResponse(
                r.getAdvrId(), r.getRevId(), r.getRuleId(),
                r.getAdvisoryTypeCd(), r.getSeverityCd(), r.getAdvrStatusCd(),
                r.getAdvrTitle(), r.getTargetReviewerId(),
                r.getGeneratedAt(), r.getFirstViewedAt(), r.getResolvedAt());
    }
}
