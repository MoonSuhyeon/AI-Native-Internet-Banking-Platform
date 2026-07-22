package com.bank.ai.audit;

import com.bank.ai.agent.AgentOpinion;
import com.bank.ai.review.event.AutoReviewEvaluatedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 에이전트 감사 로그 단건 레코드 — {@code agent_audit_log} 테이블 대응.
 *
 * <p>INSERT-ONLY 정책: 생성 후 수정·삭제 불가 (여신전문금융업법 §52의2).
 *
 * @param revId               심사 신청 ID (loan_review.id 참조)
 * @param track               TRACK_1 / TRACK_2 / TRACK_3
 * @param requestSnapshotJson PII 마스킹 완료된 AutoReviewRequest JSON
 * @param opinionJson         AgentOpinion JSON
 * @param toolCallsJson       도구 호출 이력 JSON (현 단계 빈 배열, B2에서 확장)
 * @param rawLlmResponse      LLM 원문 응답 (ai.audit.include-raw-llm-response=true 시만 채움)
 * @param piiMasked           PII 마스킹 여부 (항상 true — PiiMaskingFilter 보장)
 * @param fallbackReason      폴백 사유 코드 (정상 실행 시 null)
 */
public record AgentAuditRecord(
        Long revId,
        String track,
        String requestSnapshotJson,
        String opinionJson,
        String toolCallsJson,
        String rawLlmResponse,
        boolean piiMasked,
        String fallbackReason
) {

    /**
     * 이벤트 + 에이전트 의견으로부터 감사 레코드를 생성한다.
     *
     * <p>직렬화 실패 시 {@code "{}"}로 대체 (감사 로그 실패가 파이프라인을 중단하면 안 됨).
     */
    public static AgentAuditRecord from(AutoReviewEvaluatedEvent event,
                                        AgentOpinion opinion,
                                        ObjectMapper mapper,
                                        boolean includeRawLlmResponse) {
        String requestJson = safeSerialize(event.request(), mapper, "{}");
        String opinionJson = safeSerialize(opinion, mapper, "{}");
        String fallbackReason = opinion.fallbackReason() != null
                ? opinion.fallbackReason().name()
                : null;

        return new AgentAuditRecord(
                event.revId(),
                event.decision().track().name(),
                requestJson,
                opinionJson,
                "[]",
                includeRawLlmResponse ? null : null,   // B2 단계에서 LLM 원문 연결 예정
                true,
                fallbackReason
        );
    }

    @Slf4j
    private static final class LogHolder {}

    private static String safeSerialize(Object obj, ObjectMapper mapper, String fallback) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return fallback;
        }
    }
}
