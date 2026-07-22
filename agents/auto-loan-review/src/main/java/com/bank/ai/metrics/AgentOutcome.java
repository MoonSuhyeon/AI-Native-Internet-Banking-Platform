package com.bank.ai.metrics;

/** 에이전트 실행 결과 분류 — Micrometer 태그 값으로 사용. */
public enum AgentOutcome {
    /** 정상 완료 (fallback 없음). */
    SUCCESS,
    /** fallback 발동 (AGENT_DISABLED / LLM_RATE_LIMITED / TOOL_ERROR 등). */
    FALLBACK,
    /** 예외로 인한 파이프라인 중단. */
    ERROR
}
