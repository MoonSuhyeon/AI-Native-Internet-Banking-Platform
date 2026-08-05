package com.bank.deposit.dto.response;

import java.math.BigDecimal;

public record CashFlowSummary(
        BigDecimal totalInflow,
        BigDecimal totalOutflow,
        BigDecimal netCashFlow,
        BigDecimal estimatedSavingsAmount
) {}
