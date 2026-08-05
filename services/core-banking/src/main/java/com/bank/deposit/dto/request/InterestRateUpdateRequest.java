package com.bank.deposit.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record InterestRateUpdateRequest(
        @NotNull @Positive BigDecimal rate,
        String effectiveEndDate
) {}
