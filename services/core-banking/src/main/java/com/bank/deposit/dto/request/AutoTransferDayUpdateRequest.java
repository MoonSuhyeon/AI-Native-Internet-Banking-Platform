package com.bank.deposit.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AutoTransferDayUpdateRequest(
        @NotNull @Min(1) @Max(31) Integer autoTransferDay
) {}
