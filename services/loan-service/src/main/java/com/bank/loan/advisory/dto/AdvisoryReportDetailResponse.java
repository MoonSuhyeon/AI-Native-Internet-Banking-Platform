package com.bank.loan.advisory.dto;

import com.bank.loan.advisory.domain.ReviewAdvisoryAck;
import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.domain.ReviewAdvisorySignal;

import java.time.OffsetDateTime;
import java.util.List;

/** 리포트 상세 — Summary + 본문/payload + signals/acks 이력. */
public record AdvisoryReportDetailResponse(
        Long advrId,
        Long revId,
        Long ruleId,
        String advisoryTypeCd,
        String severityCd,
        String advrStatusCd,
        String advrTitle,
        String advrSummary,
        String advrPayload,
        Long targetReviewerId,
        OffsetDateTime generatedAt,
        OffsetDateTime firstViewedAt,
        OffsetDateTime resolvedAt,
        List<AdvisorySignalResponse> signals,
        List<AdvisoryAckHistoryItem> acks
) {
    public static AdvisoryReportDetailResponse of(ReviewAdvisoryReport r,
                                                  List<ReviewAdvisorySignal> signals,
                                                  List<ReviewAdvisoryAck> acks) {
        return new AdvisoryReportDetailResponse(
                r.getAdvrId(), r.getRevId(), r.getRuleId(),
                r.getAdvisoryTypeCd(), r.getSeverityCd(), r.getAdvrStatusCd(),
                r.getAdvrTitle(), r.getAdvrSummary(), r.getAdvrPayload(),
                r.getTargetReviewerId(),
                r.getGeneratedAt(), r.getFirstViewedAt(), r.getResolvedAt(),
                signals.stream().map(AdvisorySignalResponse::of).toList(),
                acks.stream().map(AdvisoryAckHistoryItem::of).toList());
    }
}
