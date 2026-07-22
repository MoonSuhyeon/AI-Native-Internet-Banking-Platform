package com.bank.aigateway.audit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record AuditAnalysisRequest(

        @NotBlank
        String analysisType,   // "BIAS_DETECTION" | "COMPLIANCE_VERIFICATION"

        @NotNull
        Long revId,

        @NotNull
        Long reviewerId,

        String reviewOpinionText,

        List<SignalSummary> signals,

        List<RagChunk> ragChunks
) {}
