package com.bank.aigateway.llm;

/**
 * LLM 벤더 추상화. 구현체는 provider 프로퍼티에 따라 조건부 Bean 등록.
 * - mock   : MockLlmClient  (local/test)
 * - claude : ClaudeLlmClient (production)
 */
public interface LlmClient {

    LlmResponse complete(LlmRequest request);
}
