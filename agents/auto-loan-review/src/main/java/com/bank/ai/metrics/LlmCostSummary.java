package com.bank.ai.metrics;

/**
 * LLM 단일 호출 비용 집계 VO — {@link AgentMetricsRecorder#recordLlmCall} 인자.
 *
 * @param inputTokens       입력 토큰 수
 * @param outputTokens      출력 토큰 수
 * @param estimatedUsdCost  추정 비용 (USD)
 */
public record LlmCostSummary(int inputTokens, int outputTokens, double estimatedUsdCost) {

    /** 토큰 정보 미제공 시 사용하는 영(0) 집계. */
    public static final LlmCostSummary ZERO = new LlmCostSummary(0, 0, 0.0);
}
