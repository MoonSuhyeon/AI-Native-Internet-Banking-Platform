package com.bank.deposit.dto.request;

import com.bank.deposit.domain.enums.InterestReason;
import com.bank.deposit.domain.enums.TaxBenefitType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record InterestPayRequest(
        @NotNull Long contractId,
        @NotNull Long accountId,
        @NotNull @Positive BigDecimal interestBeforeTax,
        BigDecimal interestTaxAmount,
        BigDecimal localIncomeTaxAmount,
        @NotNull BigDecimal appliedInterestRate,
        TaxBenefitType taxBenefitType,
        BigDecimal appliedTaxRate,
        InterestReason interestReason,
        String interestCalculationStartDate,
        String interestCalculationEndDate
) {}
