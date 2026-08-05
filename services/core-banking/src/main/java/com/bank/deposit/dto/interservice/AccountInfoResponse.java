package com.bank.deposit.dto.interservice;

import com.bank.deposit.domain.entity.Account;

public record AccountInfoResponse(
        String accountNo,
        String accountType,
        String accountStatus,
        String productCode,
        String openedAt,
        String closedAt,
        String branchCode,
        boolean fraudFlag,
        long version
) {
    public static AccountInfoResponse from(Account a) {
        return new AccountInfoResponse(
                a.getAccountNumber(),
                a.getAccountType() != null ? a.getAccountType().name() : null,
                a.getAccountStatus() != null ? a.getAccountStatus().name() : null,
                null,       // productCode: contract 조회 필요 — 현재 payment가 미사용 필드
                a.getOpenedAt() != null ? a.getOpenedAt().toString() : null,
                a.getClosedAt() != null ? a.getClosedAt().toString() : null,
                a.getBankCode(),
                Boolean.TRUE.equals(a.getFraudFlag()),
                a.getVersion() != null ? a.getVersion() : 0L
        );
    }
}
