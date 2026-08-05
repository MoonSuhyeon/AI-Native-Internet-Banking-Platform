package com.bank.deposit.dto.interservice;

import com.bank.deposit.domain.entity.Transaction;

public record CancelResponse(
        String cancelTransactionNo,
        String originalDepositTransactionNo,
        String accountNo,
        long amount,
        long balanceBefore,
        long balanceAfter,
        String canceledAt
) {
    public static CancelResponse from(Transaction reversal, String originalTxNo, String accountNo) {
        return new CancelResponse(
                reversal.getTransactionNumber(),
                originalTxNo,
                accountNo,
                reversal.getAmount().longValue(),
                reversal.getBalanceBefore().longValue(),
                reversal.getBalanceAfter().longValue(),
                reversal.getTransactionAt() != null ? reversal.getTransactionAt().toString() : null
        );
    }
}
