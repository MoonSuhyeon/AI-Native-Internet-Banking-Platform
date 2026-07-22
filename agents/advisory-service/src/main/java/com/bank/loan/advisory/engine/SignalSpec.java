package com.bank.loan.advisory.engine;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 룰 평가가 생성한 *수치적 근거 신호* 명세. AdvisoryEvaluator 가 이를
 * `REVIEW_ADVISORY_SIGNAL` 행으로 영속화한다.
 */
@Builder
public record SignalSpec(
        String signalKindCd,
        String signalMetric,
        BigDecimal observedValue,
        BigDecimal thresholdValue,
        BigDecimal peerBaselineValue,
        Integer sampleSize,
        String signalDetailJson,
        String observedWindowStart,
        String observedWindowEnd,
        OffsetDateTime observedAt
) {}
