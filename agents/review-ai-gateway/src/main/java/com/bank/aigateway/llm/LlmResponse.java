package com.bank.aigateway.llm;

public record LlmResponse(
        String content,
        int inputTokens,
        int outputTokens
) {}
