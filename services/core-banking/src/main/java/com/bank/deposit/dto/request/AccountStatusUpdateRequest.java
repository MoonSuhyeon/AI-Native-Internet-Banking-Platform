package com.bank.deposit.dto.request;

import com.bank.deposit.domain.enums.AccountStatus;
import jakarta.validation.constraints.NotNull;

public record AccountStatusUpdateRequest(
        @NotNull AccountStatus accountStatus
) {}
