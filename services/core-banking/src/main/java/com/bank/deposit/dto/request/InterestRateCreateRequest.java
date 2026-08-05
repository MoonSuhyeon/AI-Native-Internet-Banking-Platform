package com.bank.deposit.dto.request;

import com.bank.deposit.domain.enums.RateType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record InterestRateCreateRequest(
        @NotNull RateType rateType,
        @NotNull @Positive BigDecimal rate,
        @NotBlank String effectiveStartDate,
        Integer minimumContractPeriod,
        Integer maximumContractPeriod,
        BigDecimal minimumJoinAmount,
        BigDecimal maximumJoinAmount,
        String conditionDescription
) {}
