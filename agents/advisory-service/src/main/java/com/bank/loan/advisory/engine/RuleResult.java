package com.bank.loan.advisory.engine;

import lombok.Builder;

import java.util.List;

/**
 * 룰 1회 평가의 결과 — 발행할 리포트 1건 + 근거 신호 N건. AdvisoryEvaluator 가
 * 이를 `REVIEW_ADVISORY_REPORT` + `REVIEW_ADVISORY_SIGNAL` 로 영속화한다.
 *
 * 한 룰 1회 evaluate 호출이 여러 RuleResult 를 반환할 수도 있다 (예: BATCH 모드에서
 * 동일 룰이 여러 심사 건에 대해 각각 리포트 발행).
 */
@Builder
public record RuleResult(
        Long revId,
        String advisoryTypeCd,
        String severityCd,
        String advrTitle,
        String advrSummary,
        String advrPayloadJson,
        Long targetReviewerId,
        List<SignalSpec> signals
) {}
