package com.bank.deposit.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record SubscriptionProductRequest(
        @NotNull @Positive BigDecimal monthlyPaymentAmount,
        BigDecimal minMonthlyPayment,
        BigDecimal maxMonthlyPayment,
        BigDecimal maxRecognizedPaymentAmount
) {}
