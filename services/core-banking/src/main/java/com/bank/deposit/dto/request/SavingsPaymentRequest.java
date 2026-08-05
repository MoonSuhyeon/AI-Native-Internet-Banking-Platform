package com.bank.deposit.dto.request;

import com.bank.deposit.domain.enums.TransactionChannel;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record SavingsPaymentRequest(
        @NotNull Long accountId,
        @NotNull Long contractId,
        @NotNull @Positive BigDecimal amount,
        @NotNull @Positive Integer paymentRound,
        TransactionChannel channelType
) {}
