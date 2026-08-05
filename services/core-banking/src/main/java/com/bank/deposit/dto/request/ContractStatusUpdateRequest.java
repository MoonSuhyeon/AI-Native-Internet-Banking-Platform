package com.bank.deposit.dto.request;

import com.bank.deposit.domain.enums.ContractStatus;
import jakarta.validation.constraints.NotNull;

public record ContractStatusUpdateRequest(
        @NotNull ContractStatus contractStatus
) {}
