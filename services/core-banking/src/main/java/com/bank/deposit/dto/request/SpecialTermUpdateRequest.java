package com.bank.deposit.dto.request;

import jakarta.validation.constraints.NotBlank;

public record SpecialTermUpdateRequest(
        @NotBlank String specialTermName,
        @NotBlank String specialTermContent,
        @NotBlank String specialTermVersion,
        String changeReason
) {}
