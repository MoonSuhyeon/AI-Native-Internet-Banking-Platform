package com.bank.payment.security;

/**
 * 직원 운영 작업 감사 한 줄.
 *
 * <p>허용도 거절도 남긴다. 무엇을 실행했는지({@code parameters})를 함께 남기는 이유는,
 * "누가 대사를 돌렸다" 만으로는 어느 영업일을 다시 돌렸는지 알 수 없기 때문이다.
 * 재실행이 흔한 작업이라 그 구분이 곧 조사의 출발점이다.
 *
 * <p>{@code employeeId} 가 {@code null} 일 수 있다. 신원 없이 들어온 시도이며,
 * 그 경우가 오히려 더 봐야 할 기록이다.
 */
public record EmployeeOperationLogEntry(
        String employeeId,
        String actorRoles,
        String operation,
        String parameters,
        String decision,
        String denyReason,
        String traceId,
        String requestPath
) {
    public static final String DECISION_ALLOW = "ALLOW";
    public static final String DECISION_DENY  = "DENY";
}
