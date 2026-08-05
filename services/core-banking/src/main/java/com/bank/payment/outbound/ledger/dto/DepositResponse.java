package com.bank.payment.outbound.ledger.dto;

public record DepositResponse<T>(
        String code,        // DEP-0000 = SUCCESS
        String message,
        String timestamp,
        T data
) {}
