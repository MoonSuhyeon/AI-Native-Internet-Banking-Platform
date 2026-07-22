package com.bank.loan.advisory.dto;

import com.bank.loan.advisory.domain.ReviewAdvisoryReport;

import java.time.OffsetDateTime;

/** 격리(Quarantine) 리포트 목록 항목. */
public record QuarantineReportResponse(
        Long advrId,
        Long revId,
        Long targetReviewerId,
        String advisoryTypeCd,
        String severityCd,
        String advrTitle,
        OffsetDateTime quarantinedAt,
        OffsetDateTime generatedAt
) {
    public static QuarantineReportResponse of(ReviewAdvisoryReport r) {
        return new QuarantineReportResponse(
                r.getAdvrId(), r.getRevId(), r.getTargetReviewerId(),
                r.getAdvisoryTypeCd(), r.getSeverityCd(), r.getAdvrTitle(),
                r.getQuarantinedAt(), r.getGeneratedAt());
    }
}
