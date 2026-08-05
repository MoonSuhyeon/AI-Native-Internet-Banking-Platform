package com.bank.deposit.dto.request;

import com.bank.deposit.domain.enums.ProductStatus;
import jakarta.validation.constraints.NotNull;

public record ProductStatusUpdateRequest(
        @NotNull ProductStatus productStatus
) {}
