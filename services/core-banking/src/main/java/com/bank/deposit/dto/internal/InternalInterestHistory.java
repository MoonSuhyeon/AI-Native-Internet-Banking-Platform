package com.bank.deposit.dto.internal;

import com.bank.deposit.domain.entity.InterestHistory;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 이자 지급 한 건. 상담이 이자 내역 안내에 쓴다. */
public record InternalInterestHistory(
        Long interestId,
        Long contractId,
        Long accountId,
        BigDecimal appliedInterestRate,
        Long interestAmount,
        Long interestAfterTaxAmount,
        OffsetDateTime paidAt
) {
    public static InternalInterestHistory from(InterestHistory h) {
        return new InternalInterestHistory(
                h.getInterestId(), h.getContractId(), h.getAccountId(),
                h.getAppliedInterestRate(), h.getInterestAmount(),
                h.getInterestAfterTax(), h.getInterestPaidAt());
    }
}
