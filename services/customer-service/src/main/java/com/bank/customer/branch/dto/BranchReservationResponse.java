package com.bank.customer.branch.dto;

import com.bank.customer.branch.domain.BranchConsultationReservation;

import java.time.OffsetDateTime;

public record BranchReservationResponse(
        Long reservationId,
        Long branchId,
        String branchName,
        OffsetDateTime reservedAt,
        String topicCd,
        String memo,
        String contactPhone,
        String statusCd
) {
    public static BranchReservationResponse of(BranchConsultationReservation r, String branchName) {
        return new BranchReservationResponse(
                r.getReservationId(), r.getBranchId(), branchName, r.getReservedAt(),
                r.getTopicCd(), r.getMemo(), r.getContactPhone(), r.getStatusCd());
    }
}
