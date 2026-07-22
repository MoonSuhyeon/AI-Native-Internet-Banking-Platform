package com.bank.loan.advisory.dto;

import com.bank.loan.advisory.domain.ReviewAdvisoryAck;

import java.time.OffsetDateTime;

/** ack 이력 항목 (리포트 상세에 포함). */
public record AdvisoryAckHistoryItem(
        Long advkId,
        Long ackReviewerId,
        String ackResponseCd,
        String decisionChangeYn,
        String ackReasonCd,
        String ackRemark,
        String beforeDecisionCd,
        String afterDecisionCd,
        OffsetDateTime ackedAt
) {
    public static AdvisoryAckHistoryItem of(ReviewAdvisoryAck a) {
        return new AdvisoryAckHistoryItem(
                a.getAdvkId(), a.getAckReviewerId(),
                a.getAckResponseCd(), a.getDecisionChangeYn(),
                a.getAckReasonCd(), a.getAckRemark(),
                a.getBeforeDecisionCd(), a.getAfterDecisionCd(),
                a.getAckedAt());
    }
}
