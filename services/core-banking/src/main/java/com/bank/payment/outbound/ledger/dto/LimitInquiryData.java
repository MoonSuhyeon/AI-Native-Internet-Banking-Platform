package com.bank.payment.outbound.feign.dto;

public record LimitInquiryData(
        String accountNo,
        String date,
        Long dailyLimit,
        Long dailyUsed,
        Long dailyRemaining,
        Long monthlyLimit,
        Long monthlyUsed,
        Long monthlyRemaining,
        Long perTxLimit,
        String limitTier
) {}
