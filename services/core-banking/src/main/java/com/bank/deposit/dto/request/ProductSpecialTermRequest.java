package com.bank.deposit.dto.request;

import jakarta.validation.constraints.NotNull;

public record ProductSpecialTermRequest(
        @NotNull Long specialTermId,
        Boolean isRequired
) {}
