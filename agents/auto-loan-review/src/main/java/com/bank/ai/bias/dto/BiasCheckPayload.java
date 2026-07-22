package com.bank.ai.bias.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;

/**
 * loan-service 가 Kafka 에 발행하는 BIAS_CHECK_REQUESTED 이벤트 페이로드.
 * LoanBiasCheckRequestedPayload 와 1:1 대응.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BiasCheckPayload(
        String eventTypeCd,
        OffsetDateTime occurredAt,
        Long revId,
        Long applId,
        String revTypeCd,
        ReviewerDecision reviewerDecision,
        ReviewContext context
) {
    public static final String EVENT_TYPE_CD = "BIAS_CHECK_REQUESTED";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReviewerDecision(
            String decisionCd,
            String rejectReasonCd,
            Long approvedAmount,
            Integer approvedRateBps,
            Integer approvedPeriodMo,
            Long reviewerId,
            OffsetDateTime reviewedAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReviewContext(
            String productCd,
            String cbDecisionCd,
            Integer cbScore,
            Integer dsrRatioBps,
            Integer dsrLimitBps,
            Integer ltvRatioBps
    ) {}
}
