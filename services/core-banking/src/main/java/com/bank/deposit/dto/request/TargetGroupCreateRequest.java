package com.bank.deposit.dto.request;

import jakarta.validation.constraints.NotBlank;

public record TargetGroupCreateRequest(
        @NotBlank String targetGroupName,
        String description
) {}
