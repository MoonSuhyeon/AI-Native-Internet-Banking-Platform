package com.bank.ai.llm.prompt;

import java.util.List;

/**
 * 프롬프트 레지스트리 항목 — plan/llm-pipeline.md §6.1 의 YAML 명세와 1:1 매핑.
 *
 * <p>불변 value object. {@link PromptRegistry#get(String, int)} 로만 생성.
 * 각 필드는 YAML 의 최상위 키에 대응.
 *
 * @param id            서비스 코드에서 참조하는 식별자 (예: {@code "purpose_analysis"})
 * @param version       정수 버전 (변경 감사 키). 코드 측 상수와 일치 필수.
 * @param defaultModel  provider 기본 모델명 (예: {@code "gemini-2.5-flash"})
 * @param fallbackModel provider 장애 시 대체 모델 (예: {@code "claude-haiku-4-5"})
 * @param system        LLM system prompt 전문 (injection-safe; user 입력 절대 포함 X)
 * @param userTemplate  Mustache-style 사용자 콘텐츠 템플릿 (비워두면 서비스가 직접 구성)
 * @param outputSchema  구조화 출력 대상 클래스 FQCN (문서용; 런타임 캐스트는 호출 측 담당)
 * @param maxTokens     응답 토큰 한도 (글로벌 {@code ai.llm.max-tokens} override)
 * @param temperature   0~1 (0 권장; 구조화 응답에서 랜덤성 최소화)
 * @param changelog     버전 변경 이력 (감사·재현 목적)
 */
public record Prompt(
        String id,
        int version,
        String defaultModel,
        String fallbackModel,
        String system,
        String userTemplate,
        String outputSchema,
        int maxTokens,
        double temperature,
        List<String> changelog
) {
}
