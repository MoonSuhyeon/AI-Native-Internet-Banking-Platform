package com.bank.ai.llm.client;

/**
 * Provider 중립 LLM 호출 인터페이스 — structured output 전제.
 *
 * <p>plan/llm-pipeline.md §0 의 원칙: 모든 LLM 출력은 schema 강제. 자유 텍스트 거부.
 * 구현체:
 * <ul>
 *   <li>{@code StubLlmClient} — 결정론적 응답 (로컬·테스트)</li>
 *   <li>(예정) {@code VertexLlmClient} — Google Vertex AI Gemini</li>
 *   <li>(예정) {@code AnthropicLlmClient} — Anthropic Claude</li>
 * </ul>
 *
 * <p>{@code application.yml} 의 {@code ai.llm.provider} 가 활성 빈 결정.
 * kill switch (enabled=false) 는 {@code LlmGateway} 에서 호출 자체를 차단.
 */
public interface LlmClient {

    /**
     * @param request      이미 PII 마스킹 + injection wrap 완료된 입력
     * @param outputSchema 응답 JSON → 매핑할 record 클래스 (Jackson 호환)
     * @return outputSchema 인스턴스. provider 가 schema 위반 시 {@link LlmCallException}
     */
    <T> T call(LlmRequest request, Class<T> outputSchema);
}
