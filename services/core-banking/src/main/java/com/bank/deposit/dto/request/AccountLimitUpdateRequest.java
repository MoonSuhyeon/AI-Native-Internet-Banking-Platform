package com.bank.deposit.dto.request;

import java.math.BigDecimal;

public record AccountLimitUpdateRequest(
        BigDecimal dailyWithdrawLimit,
        Integer dailyWithdrawCountLimit,
        BigDecimal atmWithdrawLimit
) {}
