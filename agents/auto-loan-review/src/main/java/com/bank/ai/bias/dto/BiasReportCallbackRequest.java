package com.bank.ai.bias.dto;

import java.util.List;

/**
 * loan-service POST /api/internal/loan-reviews/{revId}/bias-report 콜백 바디.
 * BiasReportRequest (loan-service) 와 1:1 대응.
 */
public record BiasReportCallbackRequest(
        String severityCd,
        String summary,
        List<Finding> findings,
        String model,
        String modelVersion,
        String promptHash,
        Integer inputToken,
        Integer outputToken,
        Integer latencyMs
) {
    public record Finding(
            String code,
            String result,
            String detail
    ) {}
}
