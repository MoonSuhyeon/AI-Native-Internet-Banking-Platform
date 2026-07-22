package com.bank.aigateway.llm;

public record LlmRequest(
        String systemPrompt,
        String userPrompt,
        int maxTokens,
        double temperature
) {
    public static LlmRequest of(String systemPrompt, String userPrompt) {
        return new LlmRequest(systemPrompt, userPrompt, 2048, 0.2);
    }
}
