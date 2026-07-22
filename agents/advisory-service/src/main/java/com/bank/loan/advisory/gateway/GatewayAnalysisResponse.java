package com.bank.loan.advisory.gateway;

import java.util.List;

public record GatewayAnalysisResponse(
        String analysisType,
        String conclusion,
        String reasoningSummary,
        double confidenceScore,
        int inputTokens,
        int outputTokens,
        List<Long> citedChunkIds
) {}
