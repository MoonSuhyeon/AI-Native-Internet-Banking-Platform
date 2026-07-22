package com.bank.aigateway.consumer;

import java.time.OffsetDateTime;

/**
 * loan-service → review-ai-gateway Kafka 이벤트 페이로드.
 * loan-service의 LoanBiasCheckRequestedPayload 와 동일한 구조.
 */
public record LoanBiasCheckPayload(
        String eventTypeCd,
        OffsetDateTime occurredAt,
        Long revId,
        Long applId,
        String revTypeCd,
        ReviewerDecision reviewerDecision,
        ReviewContext context
) {
    public record ReviewerDecision(
            String decisionCd,
            String rejectReasonCd,
            Long approvedAmount,
            Integer approvedRateBps,
            Integer approvedPeriodMo,
            Long reviewerId,
            OffsetDateTime reviewedAt
    ) {}

    public record ReviewContext(
            String productCd,
            String cbDecisionCd,
            Integer cbScore,
            Integer dsrRatioBps,
            Integer dsrLimitBps,
            Integer ltvRatioBps
    ) {}
}
