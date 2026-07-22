package com.bank.loan.advisory.event;

import java.util.List;

/**
 * AI 감사 결론이 BIAS_SUSPECTED / VIOLATION_SUSPECTED 일 때 발행.
 * 리스너(알림, 관리자 에스컬레이션 등)가 비동기로 수신한다.
 */
public record QuarantineTriggeredEvent(
        Long revId,
        Long reviewerId,
        String conclusionCd,
        String analysisType,
        List<Long> advrIds
) {}
