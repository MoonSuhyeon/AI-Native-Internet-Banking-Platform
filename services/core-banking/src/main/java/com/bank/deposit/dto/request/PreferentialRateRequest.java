package com.bank.deposit.dto.request;

import java.math.BigDecimal;

public record PreferentialRateRequest(
        String conditionName,
        BigDecimal appliedRate,
        Boolean appliedYn
) {}
