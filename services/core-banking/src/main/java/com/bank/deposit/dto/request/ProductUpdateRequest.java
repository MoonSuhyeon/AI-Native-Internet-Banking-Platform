package com.bank.deposit.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record ProductUpdateRequest(
        @NotBlank String productName,
        String description,
        BigDecimal baseInterestRate
) {}
