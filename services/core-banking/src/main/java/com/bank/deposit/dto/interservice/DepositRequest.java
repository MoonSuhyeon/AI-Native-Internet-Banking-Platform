package com.bank.deposit.dto.interservice;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record DepositRequest(
        @NotBlank String accountNo,
        @NotNull @Positive Long amount,
        String currency,
        String transactionType,
        String referenceNo,
        WithdrawRequest.Counterparty counterparty,
        String memo
) {
}
