package com.bank.deposit.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record SubscriptionProductRequest(
        @NotNull @Positive Long monthlyPaymentAmount,
        Long minMonthlyPayment,
        Long maxMonthlyPayment,
        Long maxRecognizedPaymentAmount
) {}
