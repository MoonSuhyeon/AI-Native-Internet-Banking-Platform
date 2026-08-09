package com.bank.loan.advisory.dto;

import com.bank.loan.advisory.domain.ReviewAdvisorySignal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 리포트 근거 신호 응답. */
public record AdvisorySignalResponse(
        Long advsId,
        String signalKindCd,
        String signalMetric,
        BigDecimal observedValue,
        BigDecimal thresholdValue,
        BigDecimal peerBaselineValue,
        Integer sampleSize,
        String signalDetail,
        String observedWindowStart,
        String observedWindowEnd,
        OffsetDateTime observedAt
) {
    public static AdvisorySignalResponse of(ReviewAdvisorySignal s) {
        return new AdvisorySignalResponse(
                s.getAdvsId(), s.getSignalKindCd(), s.getSignalMetric(),
                s.getObservedValue(), s.getThresholdValue(), s.getPeerBaselineValue(),
                s.getSampleSize(), s.getSignalDetail(),
                s.getObservedWindowStart(), s.getObservedWindowEnd(),
                s.getObservedAt());
    }
}
