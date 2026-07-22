package com.bank.ai.privacy;

/**
 * LLM 응답에 원시 PII 패턴이 감지됐을 때 던지는 예외 — plan/llm-pipeline.md §8.
 *
 * <p>{@link PiiAwareChatClient} 및 {@link PiiMaskingFilter#assertNoPii} 가 사용.
 * 호출 측은 catch 해 {@code TemplateFallback} 으로 우회하거나 심사 파이프라인을 중단.
 *
 * <p>RuntimeException 계열 — PII 유출은 예외적 상황이므로 unchecked 로 전파.
 */
public class PiiLeakageException extends RuntimeException {

    private final String context;

    /**
     * @param context 감지 컨텍스트 식별자 (예: promptId, 서비스명). 감사 로그용.
     */
    public PiiLeakageException(String context) {
        super("LLM 응답에 PII 패턴 감지 — 컨텍스트: " + context);
        this.context = context;
    }

    public String getContext() {
        return context;
    }
}
