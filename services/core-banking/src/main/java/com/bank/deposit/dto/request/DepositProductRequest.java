package com.bank.deposit.dto.request;

import com.bank.deposit.domain.enums.DepositType;
import jakarta.validation.constraints.NotNull;

public record DepositProductRequest(
        @NotNull DepositType depositType,
        Boolean isCompoundInterest
) {}
