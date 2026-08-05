package com.bank.deposit.dto.request;

import com.bank.deposit.domain.enums.JoinChannel;
import com.bank.deposit.domain.enums.SavingType;
import com.bank.deposit.domain.enums.TaxBenefitType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record ContractCreateRequest(
        @NotBlank String customerId,
        @NotNull Long productId,
        @NotNull @Positive BigDecimal joinAmount,
        @NotNull @Positive Integer contractPeriodMonth,
        JoinChannel joinChannel,
        BigDecimal contractInterestRate,
        BigDecimal totalPreferentialRate,
        TaxBenefitType taxBenefitType,
        Boolean isAutoRenewal,
        Boolean autoTransferEnabled,
        Integer autoTransferDay,
        Long sourceAccountId,
        Long branchId,
        Long managerId,
        SavingType savingType,
        @NotBlank String accountPassword
) {}
