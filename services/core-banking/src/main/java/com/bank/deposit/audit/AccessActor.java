package com.bank.deposit.audit;

/**
 * 이 조회를 누가 요청했는가.
 *
 * <p>게이트웨이가 JWT 를 검증해 헤더로 넣어 준 값만 신뢰한다. 게이트웨이는
 * 클라이언트가 보낸 같은 이름의 헤더를 <b>먼저 지우고</b> 자기 값을 넣으므로
 * (JwtAuthenticationFilter), 여기 도달한 값은 사칭이 아니다.
 *
 * @param type       EMPLOYEE(직원 대리 조회) · CUSTOMER(본인 조회) · SERVICE(서비스 간 호출)
 * @param employeeId 직원 식별자. type 이 EMPLOYEE 일 때만 있다
 * @param customerId 고객 식별자. type 이 CUSTOMER 일 때만 있다
 * @param service    호출 서비스 이름. type 이 SERVICE 일 때만 있다
 * @param reason     조회 사유. 직원 대리 조회에서는 필수다
 * @param traceId    요청 상관키
 */
public record AccessActor(
        String type,
        Long employeeId,
        String customerId,
        String service,
        String reason,
        String traceId
) {
    public static final String EMPLOYEE = "EMPLOYEE";
    public static final String CUSTOMER = "CUSTOMER";
    public static final String SERVICE  = "SERVICE";

    public boolean isEmployee() {
        return EMPLOYEE.equals(type);
    }

    /** 직원 대리 조회는 사유 없이 허용하지 않는다. 사유 없는 열람은 사후에 설명할 수 없다. */
    public boolean lacksReason() {
        return reason == null || reason.isBlank();
    }
}
