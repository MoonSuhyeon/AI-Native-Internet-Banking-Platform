package com.bank.deposit.dto.request;

import com.bank.deposit.domain.enums.SpecialTermStatus;
import jakarta.validation.constraints.NotNull;

public record SpecialTermStatusUpdateRequest(
        @NotNull SpecialTermStatus status
) {}
