package com.bank.payment.outbound.ledger.dto;

public record BalanceInquiryData(
        String accountNo,
        Long balance,
        Long availableBalance,
        Long holdAmount,
        String currency,
        String lastTxAt,
        Integer version
) {}
