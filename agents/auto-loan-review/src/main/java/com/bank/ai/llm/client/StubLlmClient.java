package com.bank.ai.llm.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 외부 API 호출 없이 결정론적 응답을 생성하는 LLM stub.
 *
 * <p>운영 의도: 로컬 개발·CI 환경에서 LLM 의존성 없이 파이프라인 end-to-end 검증.
 * promptId 별로 등록된 응답 템플릿을 user content 의 키워드로 분기. 동일 입력 → 동일 출력
 * (Jackson 역직렬화로 schema 강제).
 *
 * <p>본 stub 은 시그널 패턴을 의도적으로 단순화 — 실제 LLM 의 의미 분석 대체 X. 인터페이스
 * 계약 (LlmClient.call(req, T.class) → T) 검증 + injection·PII 가드 통합 테스트가 1차 목적.
 *
 * <p>활성 조건: {@code ai.llm.provider=stub} (기본). 운영 provider 빈이 등록되면 비활성.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.llm", name = "provider", havingValue = "stub", matchIfMissing = true)
@RequiredArgsConstructor
public class StubLlmClient implements LlmClient {

    private final ObjectMapper objectMapper;

    /** purpose_analysis 분기 키워드. 의미 분석 X — 단순 매칭. */
    private static final Pattern VAGUE_PATTERN = Pattern.compile(
            "생활(자금|비)|기타|개인사정|용도\\s*미정|급한 일", Pattern.CASE_INSENSITIVE
    );

    @Override
    public <T> T call(LlmRequest request, Class<T> outputSchema) {
        log.debug("StubLlmClient.call: promptId={} v{} schema={}",
                request.promptId(), request.promptVer(), outputSchema.getSimpleName());
        String json = renderResponseJson(request);
        try {
            return objectMapper.readValue(json, outputSchema);
        } catch (JsonProcessingException e) {
            throw new LlmCallException(
                    "stub response schema mismatch: " + outputSchema.getSimpleName() + " ← " + json, e);
        }
    }

    /** promptId 별 결정론적 JSON 응답 생성. */
    private String renderResponseJson(LlmRequest request) {
        return switch (request.promptId()) {
            case "purpose_analysis" -> renderPurposeAnalysis(request.userContent());
            case "review_report_track1" -> renderReviewReport("TRACK_1", request.userContent());
            case "review_report_track2" -> renderReviewReport("TRACK_2", request.userContent());
            case "review_report_track3" -> renderReviewReport("TRACK_3", request.userContent());
            case "agent_reasoning_summary" -> renderAgentReasoningSummary(request.userContent());
            case "rejection_reason_draft" -> renderRejectionReasonDraft(request.userContent());
            default -> throw new LlmCallException(
                    "stub 은 promptId='" + request.promptId() + "' 미지원 — provider 구현 필요");
        };
    }

    private String renderPurposeAnalysis(String userContent) {
        boolean vague = VAGUE_PATTERN.matcher(userContent).find();
        boolean tooShort = userContent.length() < 20;
        Map<String, Object> out = new HashMap<>();
        // plausibility: 모호하지 않고 길이 충분하면 높게
        out.put("plausibility", vague ? 0.35 : (tooShort ? 0.55 : 0.85));
        out.put("specificity", vague ? 0.20 : (tooShort ? 0.40 : 0.78));
        out.put("redFlags", vague ? java.util.List.of("VAGUE_PURPOSE") : java.util.List.of());
        out.put("reasoning",
                vague ? "stub: vague purpose 감지"
                      : (tooShort ? "stub: 텍스트 짧음" : "stub: 정상 사유"));
        try {
            return objectMapper.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new LlmCallException("stub JSON 생성 실패", e);
        }
    }

    /** 에이전트 추론 요약 stub — Track 3 정보에서 결정론적 요약 생성. */
    private String renderAgentReasoningSummary(String userContent) {
        boolean hasWarning = userContent.contains("_WARNING");
        String summary = hasWarning
                ? "[stub] 정책 경고 항목이 확인되어 위험도가 상승합니다. 심사원의 추가 검토가 필요합니다."
                : "[stub] 심사 결과 회색지대에 해당하며 수치 지표가 안전 임계 근방입니다. 심사원 검토가 권고됩니다.";
        try {
            return objectMapper.writeValueAsString(java.util.Map.of("summary", summary));
        } catch (JsonProcessingException e) {
            throw new LlmCallException("stub JSON 생성 실패", e);
        }
    }

    /** 거절 통보문 초안 stub — 사유 키워드로 hard fail / PD 초과 분기. */
    private String renderRejectionReasonDraft(String userContent) {
        boolean hardFail = userContent.contains("DSR_EXCEEDED")
                || userContent.contains("LTV_EXCEEDED")
                || userContent.contains("CREDIT_SCORE_BELOW_MIN")
                || userContent.contains("DELINQUENCY_24M_PRESENT")
                || userContent.contains("AGE_BELOW_MIN");
        String notice = hardFail
                ? "[stub] 귀하의 신청은 담보비율 또는 연체 이력 기준을 충족하지 않아 반려 처리되었습니다."
                : "[stub] 귀하의 신청은 신용위험 모델 평가 결과 PD 임계를 초과하여 반려 처리되었습니다.";
        try {
            return objectMapper.writeValueAsString(java.util.Map.of("notice", notice));
        } catch (JsonProcessingException e) {
            throw new LlmCallException("stub JSON 생성 실패", e);
        }
    }

    /**
     * 트랙별 ReviewReport 결정론 응답. 인라인 정책 id 만 인용해 GroundingValidator 통과.
     * 실제 LLM 의 자연어 본문 대신 결정 sentence 만 반환 — 테스트·통합 검증 1차 목적.
     */
    private String renderReviewReport(String track, String userContent) {
        boolean hasHardFail = userContent.contains("hardFails:") && !userContent.contains("hardFails: 없음");
        Map<String, Object> out = new HashMap<>();
        out.put("track", track);
        out.put("fallbackReason", null);

        switch (track) {
            case "TRACK_1" -> {
                out.put("summary", "[stub] 자동 승인 권고. PD 가 안전여유 임계 이하라 정책 매트릭스 기준 안전 구간.");
                out.put("riskFactors", java.util.List.of());
                out.put("strengths", java.util.List.of(
                        Map.of("code", "LOW_PD",
                                "description", "PD 가 안전여유 임계 이하",
                                "citationId", "PD_THRESHOLD_MATRIX_V1")
                ));
                out.put("recommendation", "심사원 sign-off 후 승인 처리 권고.");
                out.put("citations", java.util.List.of(
                        Map.of("id", "PD_THRESHOLD_MATRIX_V1",
                                "source", "internal_policy_2026q2",
                                "text", "PD 매트릭스 정책")
                ));
            }
            case "TRACK_2" -> {
                String firstPara = hasHardFail
                        ? "[stub] 자동 반려 권고. 정책 hard constraint 위반 검출."
                        : "[stub] 자동 반려 권고. PD 가 매트릭스 임계 초과.";
                out.put("summary", firstPara
                        + "\n\n통보 시 위 사유를 명시. 통보문 초안은 RejectionNoticeService 산출 참조.");
                out.put("riskFactors", java.util.List.of(
                        Map.of("code", "POLICY_VIOLATION",
                                "description", "정책 임계 위반",
                                "weight", 1.0,
                                "citationId", "PD_THRESHOLD_MATRIX_V1")
                ));
                out.put("strengths", java.util.List.of());
                out.put("recommendation", "심사원 sign-off 후 거절 통보 처리 권고.");
                out.put("citations", java.util.List.of(
                        Map.of("id", "PD_THRESHOLD_MATRIX_V1",
                                "source", "internal_policy_2026q2",
                                "text", "PD 매트릭스 정책"),
                        Map.of("id", "AUTO_REVIEW_GOVERNANCE_V1",
                                "source", "internal_policy_2026q2",
                                "text", "자동심사 거버넌스 — ML 은 변별력만")
                ));
            }
            case "TRACK_3" -> {
                out.put("summary",
                        "[stub] [위험요인] PD 가 회색지대 — 안전여유 초과, 매트릭스 이하."
                                + "\n\n[강점] hard constraint 위반 없음."
                                + "\n\n[권고] 심사원 심층 판단 — 거래내역·신청 사유 정합성 검토.");
                out.put("riskFactors", java.util.List.of(
                        Map.of("code", "PD_GRAY_ZONE",
                                "description", "PD 회색지대",
                                "weight", 0.5,
                                "citationId", "PD_THRESHOLD_MATRIX_V1")
                ));
                out.put("strengths", java.util.List.of(
                        Map.of("code", "RULE_PASS",
                                "description", "정책 hard constraint 통과",
                                "citationId", "AUTO_REVIEW_GOVERNANCE_V1")
                ));
                out.put("recommendation", "심사원 심층 판단 필수.");
                out.put("citations", java.util.List.of(
                        Map.of("id", "PD_THRESHOLD_MATRIX_V1",
                                "source", "internal_policy_2026q2",
                                "text", "PD 매트릭스 정책")
                ));
            }
        }
        try {
            return objectMapper.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            throw new LlmCallException("stub JSON 생성 실패", e);
        }
    }
}
