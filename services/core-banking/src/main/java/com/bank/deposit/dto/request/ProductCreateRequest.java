package com.bank.deposit.dto.request;

import com.bank.deposit.domain.enums.ProductType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record ProductCreateRequest(
        @NotNull ProductType productType,
        @NotBlank String productName,
        String description,
        Long departmentId,
        @PositiveOrZero BigDecimal baseInterestRate,
        BigDecimal minJoinAmount,
        BigDecimal maxJoinAmount,
        Integer minPeriodMonth,
        Integer maxPeriodMonth,
        Boolean isEarlyTerminationAllowed,
        Boolean isTaxBenefitAvailable,
        Boolean isAutoRenewalAvailable,
        String releasedAt
) {}
