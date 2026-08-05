package com.bank.deposit.dto.request;

import com.bank.deposit.domain.enums.TransactionChannel;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record DepositRequest(
        @NotNull Long accountId,
        @NotNull @Positive BigDecimal amount,
        TransactionChannel channelType,
        String transactionMemo,
        String depositorCustomerId,
        String depositorName
) {}
