package com.bank.deposit.dto.request;

import jakarta.validation.constraints.NotBlank;

public record SpecialTermCreateRequest(
        @NotBlank String specialTermName,
        @NotBlank String specialTermContent,
        String specialTermSummary,
        Boolean isRequired,
        @NotBlank String specialTermVersion,
        String startedAt,
        String endedAt
) {}
