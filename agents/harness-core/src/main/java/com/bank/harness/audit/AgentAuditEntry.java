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
        Instant recordedAt
) {
    public AgentAuditEntry {
        if (recordedAt == null) recordedAt = Instant.now();
        if (requestJson == null || requestJson.isBlank()) requestJson = "{}";
        if (outputJson == null || outputJson.isBlank()) outputJson = "{}";
        if (toolCallsJson == null || toolCallsJson.isBlank()) toolCallsJson = "[]";
    }
}
