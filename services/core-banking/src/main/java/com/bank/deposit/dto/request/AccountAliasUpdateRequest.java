package com.bank.deposit.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AccountAliasUpdateRequest(
        @NotBlank @Size(max = 100) String accountAlias
) {}
