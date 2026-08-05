package com.bank.deposit.dto.response;

import java.util.List;

public record ProductRecommendResponse(
        String customerId,
        int analysisPeriodMonth,
        CashFlowSummary cashFlow,
        List<RecommendedProduct> recommendations,
        String fallbackReason
) {
    public ProductRecommendResponse(String customerId, int analysisPeriodMonth,
                                    CashFlowSummary cashFlow, List<RecommendedProduct> recommendations) {
        this(customerId, analysisPeriodMonth, cashFlow, recommendations, null);
    }
}
