package com.bank.deposit.dto.interservice;

import com.bank.deposit.domain.entity.Transaction;

public record TransactionResponse(
        String depositTransactionNo,
        String accountNo,
        long amount,
        long balanceBefore,
        long balanceAfter,
        String transactionAt,
        String transactionType
) {
    public static TransactionResponse from(Transaction tx, String accountNo) {
        return new TransactionResponse(
                tx.getTransactionNumber(),
                accountNo,
                tx.getAmount().longValue(),
                tx.getBalanceBefore().longValue(),
                tx.getBalanceAfter().longValue(),
                tx.getTransactionAt() != null ? tx.getTransactionAt().toString() : null,
                tx.getTransactionType() != null ? tx.getTransactionType().name() : null
        );
    }
}
