package com.bank.deposit.dto.interservice;

import com.bank.deposit.domain.entity.Account;

public record BalanceResponse(
        String accountNo,
        long balance,
        long availableBalance,
        long holdAmount,
        String currency,
        String lastTxAt,
        long version
) {
    public static BalanceResponse from(Account a) {
        long bal = a.getBalance().longValue();
        long hold = a.getHoldAmount() != null ? a.getHoldAmount().longValue() : 0L;
        return new BalanceResponse(
                a.getAccountNumber(),
                bal,
                bal - hold,
                hold,
                a.getCurrency(),
                a.getLastTransactionAt() != null ? a.getLastTransactionAt().toString() : null,
                a.getVersion() != null ? a.getVersion() : 0L
        );
    }
}
