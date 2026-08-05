package com.bank.deposit.dto.interservice;

public record LimitResponse(
        String accountNo,
        String date,
        long dailyLimit,
        long dailyUsed,
        long dailyRemaining,
        long monthlyLimit,
        long monthlyUsed,
        long monthlyRemaining,
        long perTxLimit,
        String limitTier
) {
}
