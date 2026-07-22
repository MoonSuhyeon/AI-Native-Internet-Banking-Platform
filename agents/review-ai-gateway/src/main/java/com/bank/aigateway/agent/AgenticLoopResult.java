package com.bank.aigateway.agent;

public record AgenticLoopResult(
        String text,
        int inputTokens,
        int outputTokens,
        int turnsUsed,
        boolean timedOut
) {}
