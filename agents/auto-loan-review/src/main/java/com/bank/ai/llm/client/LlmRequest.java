package com.bank.ai.llm.client;

/**
 * Provider 중립 LLM 호출 요청. structured output 전제 — output schema 는 호출 측이 별도 전달.
 *
 * <p>plan/llm-pipeline.md §3 의 추상화. Spring AI 의존성 없이 자체 thin abstraction 으로
 * 시작 (외부 의존성 최소화). 운영 provider (Vertex/Anthropic) 도입 시 동일 인터페이스 구현체만 추가.
 *
 * @param promptId    메트릭·로그 식별자 (예: "purpose_analysis")
 * @param promptVer   prompt YAML 버전 (변경 감사)
 * @param system      LLM system prompt — 절대 user 입력 포함 X (인젝션 방어)
 * @param userContent {@code PromptInjectionDefense} 로 이미 wrap 된 user content
 * @param maxTokens   응답 토큰 한도
 * @param temperature 0~1, 0 권장 (구조화 응답)
 */
public record LlmRequest(
        String promptId,
        int promptVer,
        String system,
        String userContent,
        int maxTokens,
        double temperature
) {
}
