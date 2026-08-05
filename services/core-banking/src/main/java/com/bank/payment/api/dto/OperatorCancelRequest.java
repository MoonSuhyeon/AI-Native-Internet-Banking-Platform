package com.bank.payment.api.dto;

public record OperatorCancelRequest(
        String operatorId,
        String reason
) {}
