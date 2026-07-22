package com.bank.ai.llm.report;

import com.bank.ai.rule.domain.Track;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * LLM 산출 트랙별 심사 리포트 — plan/llm-pipeline.md §5.
 *
 * <p>심사원 대시보드 카드 + LOAN_REVIEW.report_json 저장 + 거절 통보문 grounding 의 source of truth.
 * 동일 입력 + 동일 prompt 버전 + 동일 정책으로 결정적이어야 함 (LLM 재시도 시).
 *
 * <p>structured output 강제 — provider 가 본 record 의 필드를 정확히 채워야 함.
 * 트랙별 톤 차별화:
 * <ul>
 *   <li>TRACK_1: summary 1 문단 + strengths 위주, citations ≥ 1 (정책 근거)</li>
 *   <li>TRACK_2: summary 2 문단 (거절 사유 + 통보문 초안) + riskFactors, citations ≥ 2 (법령·정책)</li>
 *   <li>TRACK_3: summary 3 문단 (위험 / 강점 / 권고), riskFactors + strengths 모두</li>
 * </ul>
 *
 * @param track             RuleEngine 트랙 분기 결과 (입력과 동일해야 함 — 검증 대상)
 * @param summary           한국어 본문 (트랙별 문단 수)
 * @param riskFactors       위험 요인 (Track 2/3 중심)
 * @param strengths         강점 (Track 1/3 중심)
 * @param recommendation    권고 한 문장 (모든 트랙)
 * @param citations         인용된 정책·법령 (Track 2 는 ≥ 2 강제, GroundingValidator 검증)
 * @param fallbackReason    결정론 fallback 사유 (LLM 호출 성공 시 null)
 */
public record ReviewReport(
        Track track,
        String summary,
        List<RiskFactor> riskFactors,
        List<Strength> strengths,
        String recommendation,
        List<Citation> citations,
        String fallbackReason
) {

    @JsonCreator
    public ReviewReport(
            @JsonProperty("track") Track track,
            @JsonProperty("summary") String summary,
            @JsonProperty("riskFactors") List<RiskFactor> riskFactors,
            @JsonProperty("strengths") List<Strength> strengths,
            @JsonProperty("recommendation") String recommendation,
            @JsonProperty("citations") List<Citation> citations,
            @JsonProperty("fallbackReason") String fallbackReason
    ) {
        this.track = track;
        this.summary = summary != null ? summary : "";
        this.riskFactors = riskFactors != null ? List.copyOf(riskFactors) : List.of();
        this.strengths = strengths != null ? List.copyOf(strengths) : List.of();
        this.recommendation = recommendation != null ? recommendation : "";
        this.citations = citations != null ? List.copyOf(citations) : List.of();
        this.fallbackReason = fallbackReason;  // null OK
    }

    public boolean isFallback() {
        return fallbackReason != null;
    }

    /**
     * @param code         도메인 코드 (예: DSR_HIGH, CREDIT_SCORE_LOW)
     * @param description  심사원 노출용 한국어 설명
     * @param weight       0~1 — 트랙 분기에서 차지하는 비중 (LLM 의 자체 판단)
     * @param citationId   {@link Citation#id()} 참조 — 없으면 null (단, GroundingValidator 가 reject 검토)
     */
    public record RiskFactor(
            String code,
            String description,
            double weight,
            String citationId
    ) {
        @JsonCreator
        public RiskFactor(
                @JsonProperty("code") String code,
                @JsonProperty("description") String description,
                @JsonProperty("weight") double weight,
                @JsonProperty("citationId") String citationId
        ) {
            this.code = code;
            this.description = description;
            this.weight = Math.max(0.0, Math.min(1.0, weight));
            this.citationId = citationId;
        }
    }

    public record Strength(
            String code,
            String description,
            String citationId
    ) {
        @JsonCreator
        public Strength(
                @JsonProperty("code") String code,
                @JsonProperty("description") String description,
                @JsonProperty("citationId") String citationId
        ) {
            this.code = code;
            this.description = description;
            this.citationId = citationId;
        }
    }

    /**
     * 인용 — Phase 1.5/1.6 는 application.yml 인라인 정책 텍스트 id 참조.
     * Phase 1.7 RAG 도입 시 id 의 의미가 PolicyChunk vector store id 로 swap (schema 비변경).
     */
    public record Citation(
            String id,
            String source,
            String text
    ) {
        @JsonCreator
        public Citation(
                @JsonProperty("id") String id,
                @JsonProperty("source") String source,
                @JsonProperty("text") String text
        ) {
            this.id = id;
            this.source = source != null ? source : "";
            this.text = text != null ? text : "";
        }
    }
}
