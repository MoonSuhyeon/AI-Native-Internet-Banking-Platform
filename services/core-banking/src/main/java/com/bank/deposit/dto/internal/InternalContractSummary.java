package com.bank.deposit.dto.internal;

import com.bank.deposit.domain.entity.Contract;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 상담·만기 안내에 필요한 만큼만 담은 계약 요약. */
public record InternalContractSummary(
        Long contractId,
        String contractNumber,
        Long joinAmount,
        BigDecimal contractInterestRate,
        LocalDate startedAt,
        LocalDate maturityAt,
        String contractStatus
) {
    public static InternalContractSummary from(Contract c) {
        return new InternalContractSummary(
                c.getContractId(),
                c.getContractNumber(),
                c.getJoinAmount(),
                c.getContractInterestRate(),
                c.getStartedAt(),
                c.getMaturityAt(),
                c.getContractStatus() != null ? c.getContractStatus().name() : null
        );
    }
}
