package com.bank.deposit.dto.request;

import java.math.BigDecimal;

public record AccountLimitUpdateRequest(
        Long dailyWithdrawLimit,
        Integer dailyWithdrawCountLimit,
        Long atmWithdrawLimit
) {}
