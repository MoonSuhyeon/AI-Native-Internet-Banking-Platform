package com.bank.deposit.dto.request;

import com.bank.deposit.domain.enums.SavingType;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record SavingsProductRequest(
        @NotNull SavingType savingType,
        BigDecimal monthlyPaymentMinAmount,
        BigDecimal monthlyPaymentMaxAmount
) {}
