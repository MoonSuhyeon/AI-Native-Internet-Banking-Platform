package com.bank.loan.advisory.dto;

import com.bank.loan.advisory.domain.ReviewAdvisoryAck;

import java.time.OffsetDateTime;

/** ack 적재 응답. */
public record AdvisoryAckResponse(
        Long advkId,
        Long advrId,
        String ackResponseCd,
        String decisionChangeYn,
        OffsetDateTime ackedAt,
        Long ackReviewerId
) {
    public static AdvisoryAckResponse of(ReviewAdvisoryAck a) {
        return new AdvisoryAckResponse(
                a.getAdvkId(), a.getAdvrId(),
                a.getAckResponseCd(), a.getDecisionChangeYn(),
                a.getAckedAt(), a.getAckReviewerId());
    }
}
