package com.bank.payment.domain.service;

public record ExternalValidationResult(
        String senderHolderName,
        String receiverHolderName
) {}
