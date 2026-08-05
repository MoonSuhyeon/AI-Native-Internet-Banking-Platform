package com.bank.deposit.dto.request;

import com.bank.deposit.domain.enums.ProductType;
import com.bank.deposit.domain.enums.SavingType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AccountCreateRequest(
        @NotBlank String customerId,
        @NotNull Long contractId,
        @NotNull ProductType accountType,
        SavingType savingType,
        String accountAlias,
        @NotBlank String accountPassword
) {
}
