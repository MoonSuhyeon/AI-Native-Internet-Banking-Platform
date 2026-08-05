package com.bank.harness.audit;

import java.time.Instant;

/**
 * 에이전트 판단 1건의 감사 기록.
 *
 * <p>도메인 타입이 들어오지 않는다. 대출 심사의 {@code revId} 도, 사기 조사의 케이스 번호도
 * {@code subjectType} + {@code subjectId} 문자열 쌍으로 들어온다. 이렇게 두지 않으면
 * 대출 이외의 에이전트가 이 기록을 쓸 수 없고, 실제로 그래서 감사 로그가
 * auto-loan-review 에만 있었다.
 *
 * @param agentName   판단 주체 (auto-loan-review, fraud-investigation, consultation …)
 * @param subjectType 무엇에 대한 판단인가 (LOAN_REVIEW, FRAUD_CASE, CONSULT_SESSION …)
 * @param subjectId   그 대상의 식별자. 도메인마다 타입이 다르므로 문자열로 받는다.
 * @param traceId     같은 실행의 추적 식별자.
 *                    <b>이 필드가 감사와 추적을 잇는다.</b> 기존 구현에는 없어서
 *                    "이 판단이 어떤 LLM 호출에서 나왔는가"를 사후에 따라갈 수 없었다.
 * @param requestJson 판단에 들어간 입력 스냅샷
 * @param outputJson  판단 결과
 * @param toolCallsJson 호출한 툴 목록
 * @param rawLlmResponse LLM 원문. 추적에는 4,000자로 잘려 실리므로 전문은 여기에 남는다.
 * @param piiMasked   PII 마스킹 적용 여부
 * @param fallbackReason 모델 실패로 규칙 기반 결과를 쓴 경우 그 사유
 * @param recordedAt  기록 시각
 * @param decisionKind 판단의 종류. 한 대상에 판단이 여러 번 있을 수 있다 —
 *                     사기조사는 사건 하나에 권고와 승인 후 실행이 따로 남고,
 *                     둘 사이에 사람이 있어 합칠 수 없다. 기본값 {@value #KIND_DECISION}.
 * @param actorId     이 기록을 만든 사람. 사람 승인을 받는 에이전트에서
 *                    "누가 승인했는가"는 감사의 핵심이다.
 *                    자율 판단(사람 개입 없음)이면 null 이며, 그것도 사실이라 남긴다.
 * @param actorRoles 그 사람의 권한 역할 (JSON 배열)
 */
public record AgentAuditEntry(
        String agentName,
        String subjectType,
        String subjectId,
        String traceId,
        String requestJson,
        String outputJson,
        String toolCallsJson,
        String rawLlmResponse,
        boolean piiMasked,
        String fallbackReason,
        Instant recordedAt,
        String decisionKind,
        String actorId,
        String actorRoles
) {
    /** 사람 개입 없이 에이전트가 내린 보통의 판단. */
    public static final String KIND_DECISION = "DECISION";

    public AgentAuditEntry {
        if (recordedAt == null) recordedAt = Instant.now();
        if (requestJson == null || requestJson.isBlank()) requestJson = "{}";
        if (outputJson == null || outputJson.isBlank()) outputJson = "{}";
        if (toolCallsJson == null || toolCallsJson.isBlank()) toolCallsJson = "[]";
        if (decisionKind == null || decisionKind.isBlank()) decisionKind = KIND_DECISION;
        if (actorRoles == null || actorRoles.isBlank()) actorRoles = "[]";
    }

    /**
     * 사람이 개입하지 않는 판단용 축약 생성자.
     *
     * <p>행위자·판단종류를 뒤에 붙일 때 기존 호출부를 전부 고치지 않기 위한 것이 아니라,
     * <b>대부분의 판단에 사람이 없다는 사실</b>을 그대로 두기 위한 것이다.
     * HITL 이 있는 에이전트는 정식 생성자로 행위자를 반드시 채운다.
     */
    public AgentAuditEntry(String agentName, String subjectType, String subjectId, String traceId,
                           String requestJson, String outputJson, String toolCallsJson,
                           String rawLlmResponse, boolean piiMasked, String fallbackReason,
                           Instant recordedAt) {
        this(agentName, subjectType, subjectId, traceId, requestJson, outputJson, toolCallsJson,
                rawLlmResponse, piiMasked, fallbackReason, recordedAt, KIND_DECISION, null, "[]");
    }
}
