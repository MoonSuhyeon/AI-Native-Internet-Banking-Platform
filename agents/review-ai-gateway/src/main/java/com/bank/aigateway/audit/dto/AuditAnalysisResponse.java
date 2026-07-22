package com.bank.aigateway.audit.dto;

import java.util.List;

public record AuditAnalysisResponse(
        String analysisType,
        String conclusion,
        String reasoningSummary,
        double confidenceScore,
        int inputTokens,
        int outputTokens,
        List<Long> citedChunkIds
) {}
