package com.bank.payment.security;

/**
 * 인가 판정 감사 한 줄. [2]
 *
 * <p>허용도 거절도 남긴다. 특히 <b>거절을 빼면 안 된다</b> — 권한 없는 서비스가 반복
 * 호출한 흔적이 사라지면, 그것이 설정 실수인지 침해 시도인지 나중에 구분할 수 없다.
 *
 * <p>{@code serviceId} 가 {@code null} 일 수 있다. 자격증명 자체가 틀려 신원을 세우지
 * 못한 호출이며, 그 경우가 오히려 더 봐야 할 기록이다.
 */
public record ServiceAuthorizationLogEntry(
        String serviceId,
        String operation,
        String decision,
        String denyReason,
        String idempotencyKey,
        Long amount,
        String senderAccountNo,
        String traceId,
        String requestPath
) {
    public static final String DECISION_ALLOW = "ALLOW";
    public static final String DECISION_DENY  = "DENY";
}
