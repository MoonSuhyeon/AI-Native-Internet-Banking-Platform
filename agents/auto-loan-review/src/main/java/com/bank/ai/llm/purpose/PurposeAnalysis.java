package com.bank.ai.llm.purpose;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * LLM 산출 신청 사유 분석 — plan/llm-pipeline.md §4.
 *
 * <p>ML feature 합류 + 심사원 노출용 reasoning.
 * structured output 강제 — provider 가 본 record 의 필드를 정확히 채워야 함.
 *
 * @param plausibility    0~1, 신청 사유 + 페르소나 + 금액 일관성
 * @param specificity     0~1, 구체성 (vague 사유 vs 구체적 용처)
 * @param redFlags        검출된 위험 신호 코드
 * @param reasoning       감사용 한국어 한 줄 (심사원 미노출 — audit_json 저장 권장)
 */
public record PurposeAnalysis(
        double plausibility,
        double specificity,
        List<RedFlag> redFlags,
        String reasoning
) {

    @JsonCreator
    public PurposeAnalysis(
            @JsonProperty("plausibility") double plausibility,
            @JsonProperty("specificity") double specificity,
            @JsonProperty("redFlags") List<RedFlag> redFlags,
            @JsonProperty("reasoning") String reasoning
    ) {
        this.plausibility = clamp01(plausibility);
        this.specificity = clamp01(specificity);
        this.redFlags = redFlags != null ? List.copyOf(redFlags) : List.of();
        this.reasoning = reasoning != null ? reasoning : "";
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    public enum RedFlag {
        /** 신청 금액 vs 소득 분위 괴리 */
        AMOUNT_PERSONA_MISMATCH,
        /** "생활자금" 단독 등 모호한 사유 */
        VAGUE_PURPOSE,
        /** 주담대인데 사업자금 등 상품-사유 불일치 */
        PURPOSE_PRODUCT_MISMATCH,
        /** 긴급함·간곡함 과도 — 사기·다급 거래 의심 */
        EMOTIONAL_LANGUAGE,
        /** 프롬프트 인젝션 패턴 — PromptInjectionDefense 가 부여 */
        INSTRUCTION_INJECTION_SUSPECT
    }
}
