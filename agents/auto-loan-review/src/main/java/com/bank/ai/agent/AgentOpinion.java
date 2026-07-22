package com.bank.ai.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 사전 심사 에이전트 최종 출력 — pre-review-agent-plan.md §출력.
 *
 * <p>loan_review.agent_opinion_json (JSONB) 에 직렬화되어 저장. schema_version 필드로
 * 컨슈머 호환성 보장 — v1 이후 필드 추가 시 버전 올림.
 *
 * <p>fallback_reason != null 이면 나머지 분석 필드는 신뢰 불가(기본값).
 *
 * @param schemaVersion     스키마 버전 ("v1")
 * @param decisionScore     HMDA decision 모델 P(APPROVE) (0~1)
 * @param pdScore           PD 모델 P(default_12m) (0~1)
 * @param riskLevel         내부 리스크 분류 (LOW/MEDIUM/HIGH)
 * @param policyFlags       소프트 정책 경고 코드 목록 (예: DSR_THRESHOLD_WARNING)
 * @param reasoningSummary  LLM 생성 한국어 근거 1~2문장. fallback 시 템플릿 문장.
 * @param simulationResults What-if 시뮬레이션 결과 목록 (Track 3 only; Track 1 skip)
 * @param disagreement      reasoning_summary 톤이 Track 결정과 의미적으로 반대일 때 true (A5)
 * @param fallbackReason    정상 실행 시 null; 비정상 시 사유 코드
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AgentOpinion(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("decision_score") Double decisionScore,
        @JsonProperty("pd_score") Double pdScore,
        @JsonProperty("risk_level") RiskLevel riskLevel,
        @JsonProperty("policy_flags") List<String> policyFlags,
        @JsonProperty("reasoning_summary") String reasoningSummary,
        @JsonProperty("simulation_results") List<SimulationResult> simulationResults,
        boolean disagreement,
        @JsonProperty("fallback_reason") FallbackReason fallbackReason
) {

    /** 분석 결과로 AgentOpinion 생성 (정상 경로). */
    public static AgentOpinion of(
            double decisionScore,
            double pdScore,
            RiskLevel riskLevel,
            List<String> policyFlags,
            String reasoningSummary,
            List<SimulationResult> simulationResults,
            boolean disagreement
    ) {
        return new AgentOpinion(
                "v1", decisionScore, pdScore, riskLevel,
                policyFlags, reasoningSummary, simulationResults,
                disagreement, null
        );
    }

    /** fallback 경로 — 분석 건너뜀. */
    public static AgentOpinion fallback(FallbackReason reason) {
        return new AgentOpinion(
                "v1", null, null, null,
                List.of(), fallbackSummary(reason), List.of(),
                false, reason
        );
    }

    private static String fallbackSummary(FallbackReason reason) {
        return switch (reason) {
            case LLM_RATE_LIMITED      -> "에이전트 분석을 수행하지 못했습니다 (분당 요청 한도 초과).";
            case LLM_DAILY_CAP_EXCEEDED -> "에이전트 분석을 수행하지 못했습니다 (일간 요청 한도 초과).";
            case GROUNDING_FAILED      -> "에이전트 분석 결과를 검증하지 못했습니다 (그라운딩 실패).";
            case LOOP_GUARD_HIT        -> "에이전트가 분석 루프 한도에 도달했습니다.";
            case TOOL_ERROR            -> "에이전트 도구 실행 중 오류가 발생했습니다.";
            case AGENT_TIMEOUT         -> "에이전트 분석 시간 초과 (30s).";
            case AGENT_DISABLED        -> "에이전트 기능이 비활성화되어 있습니다.";
        };
    }
}
