package com.bank.deposit.dto.request;

public record DepositContractRequest(
        Boolean autoTransferEnabled,
        Integer autoTransferDay
) {}
