package com.bank.deposit.dto.request;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record AppliedRateRequest(
        @NotNull Long rateId,
        @NotNull BigDecimal appliedRate,
        Boolean conditionVerifiedYn
) {}
