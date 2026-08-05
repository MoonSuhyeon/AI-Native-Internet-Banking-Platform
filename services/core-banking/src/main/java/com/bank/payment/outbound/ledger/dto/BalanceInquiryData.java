package com.bank.payment.outbound.feign.dto;

public record BalanceInquiryData(
        String accountNo,
        Long balance,
        Long availableBalance,
        Long holdAmount,
        String currency,
        String lastTxAt,
        Integer version
) {}
