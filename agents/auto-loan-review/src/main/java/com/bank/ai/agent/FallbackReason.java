package com.bank.ai.agent;

/**
 * 에이전트 fallback 원인 — pre-review-agent-plan.md §출력.
 *
 * <p>null = 정상 실행. 프론트엔드가 이 값으로 안전하게 분기 가능하도록 closed enum 사용.
 */
public enum FallbackReason {
    /** RPM 초과 — 분당 요청 한도(기본 15) 소진 */
    LLM_RATE_LIMITED,
    /** RPD 초과 — 일간 요청 한도(기본 1500) 소진 */
    LLM_DAILY_CAP_EXCEEDED,
    /** 수치 클레임 그라운딩 검증 실패 — 환각 방어 (A5) */
    GROUNDING_FAILED,
    /** 도구 호출 횟수 초과 — loop guard (A4) */
    LOOP_GUARD_HIT,
    /** 도구 실행 예외 */
    TOOL_ERROR,
    /** 전체 에이전트 run 30s 타임아웃 초과 */
    AGENT_TIMEOUT,
    /** ai.agent.enabled=false — kill switch 활성 */
    AGENT_DISABLED
}
