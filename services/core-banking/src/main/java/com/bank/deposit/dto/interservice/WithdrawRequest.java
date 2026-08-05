package com.bank.deposit.dto.interservice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record WithdrawRequest(
        @NotBlank String accountNo,
        @NotNull @Positive Long amount,
        String currency,
        String transactionType,
        String referenceNo,
        Counterparty counterparty,
        String memo
) {
    public record Counterparty(
            String bankCode,
            String accountNo,
            String holderName,
            String passbookDisplay
    ) {}
}
