package com.bank.ai.llm.client;

/**
 * LLM 호출 실패 — provider 통신 오류·schema 위반·timeout 통합.
 *
 * <p>Service 측 catch 후 {@code TemplateFallback} 로 우회. 사용자 응답엔 전파 X.
 */
public class LlmCallException extends RuntimeException {

    public LlmCallException(String message) {
        super(message);
    }

    public LlmCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
