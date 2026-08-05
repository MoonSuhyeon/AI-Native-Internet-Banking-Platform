package com.bank.deposit.dto.request;

public record ContractTerminateRequest(
        String terminationReason,
        Long targetAccountId
) {}
