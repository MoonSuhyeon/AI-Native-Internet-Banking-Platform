package com.bank.ai.llm.support;

/**
 * 일일 토큰 cap 초과 — plan/llm-pipeline.md §9.
 *
 * <p>{@link LlmCostMeter#checkCapOrThrow} 가 던진다. 호출 측 서비스
 * ({@code PurposeAnalysisService}, {@code ReviewReportService})는 이 예외를
 * catch 해 {@code TemplateFallback} 으로 우회.
 *
 * <p>RuntimeException 계열 — checked 가 아닌 이유: LLM 호출 경로 전체에 propagate
 * 시키기보다 개별 서비스 경계에서 조용히 처리하는 것이 더 안전.
 */
public class LlmCostExceededException extends RuntimeException {

    private final long dailyTotalTokens;
    private final long cap;

    public LlmCostExceededException(long dailyTotalTokens, long cap) {
        super("일일 토큰 cap 초과 — 누적: %,d / 한도: %,d".formatted(dailyTotalTokens, cap));
        this.dailyTotalTokens = dailyTotalTokens;
        this.cap = cap;
    }

    /** 호출 시점까지의 당일 누적 토큰 (input + output). */
    public long getDailyTotalTokens() {
        return dailyTotalTokens;
    }

    /** 설정된 일일 한도. */
    public long getCap() {
        return cap;
    }
}
