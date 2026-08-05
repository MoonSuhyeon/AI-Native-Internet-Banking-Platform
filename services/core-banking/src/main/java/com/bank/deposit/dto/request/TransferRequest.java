package com.bank.deposit.dto.request;

import com.bank.deposit.domain.enums.TransactionChannel;
import com.bank.deposit.domain.enums.TransferType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record TransferRequest(
        @NotNull Long fromAccountId,
        Long toAccountId,
        String toAccountNo,
        @NotNull @Positive BigDecimal amount,
        TransferType transferType,
        String counterpartyBankCode,
        String counterpartyBankName,
        String counterpartyName,
        TransactionChannel channelType,
        String transactionMemo,
        String idempotencyKey
) {}
