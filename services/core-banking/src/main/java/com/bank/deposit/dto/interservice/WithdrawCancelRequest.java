package com.bank.deposit.dto.interservice;

import jakarta.validation.constraints.NotBlank;

public record WithdrawCancelRequest(
        @NotBlank String originalDepositTransactionNo,
        @NotBlank String accountNo,
        Long amount,
        String reason,
        String referenceNo
) {
}
