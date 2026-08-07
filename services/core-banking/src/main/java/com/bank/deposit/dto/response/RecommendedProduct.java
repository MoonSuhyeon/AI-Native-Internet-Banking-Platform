package com.bank.deposit.dto.response;

import java.math.BigDecimal;

public record RecommendedProduct(
        Long productId,
        String productName,
        String productType,
        BigDecimal baseInterestRate,
        BigDecimal bestRate,
        Long minJoinAmount,
        Long maxJoinAmount,
        Integer minPeriodMonth,
        Integer maxPeriodMonth,
        String reason
) {}
