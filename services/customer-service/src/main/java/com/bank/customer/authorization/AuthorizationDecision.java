package com.bank.customer.authorization;

/**
 * 인가 판단 결과.
 *
 * <p><b>왜 거절도 200 으로 돌려주는가.</b> 이것은 자원을 주는 API 가 아니라
 * <b>판단만 돌려주는 API</b>(policy decision point)다. 부르는 쪽은 거절이어도
 * 행위자 정보가 있어야 감사에 "누가 무엇을 보려다 막혔다" 를 남길 수 있다.
 * 403 으로 던지면 그 정보가 같이 사라진다.
 *
 * <p>자원 접근의 최종 통제는 여전히 자원을 가진 서비스(core-banking)에 있다.
 * 여기서 ALLOW 가 나왔다고 자원이 열리는 것이 아니다.
 *
 * @param decision     ALLOW · DENY
 * @param denyCode     거절 사유 코드. ALLOW 면 null
 * @param employeeId   판단 대상 직원
 * @param role         직원의 역할(BankRole 이름). 감사 스냅샷용
 * @param branchCode   소속 지점. 감사 스냅샷용
 * @param employeeName 직원명 스냅샷. 나중에 직원이 바뀌어도 그때 누구였는지 남는다
 */
public record AuthorizationDecision(
        String decision,
        String denyCode,
        Long employeeId,
        String role,
        String branchCode,
        String employeeName
) {
    public static final String ALLOW = "ALLOW";
    public static final String DENY  = "DENY";

    /** 직원을 못 찾았거나 퇴사·정지 상태다. party_role 까지 함께 본다. */
    public static final String DENY_EMPLOYEE_INACTIVE = "EMPLOYEE_INACTIVE";
    /** 역할은 있으나 이 자원·행위가 허용되지 않는다. */
    public static final String DENY_ROLE_NOT_PERMITTED = "ROLE_NOT_PERMITTED";
    /** 고객 금융정보 열람인데 사유가 없다. */
    public static final String DENY_REASON_REQUIRED = "REASON_REQUIRED";
    /** 정책이 모르는 자원·행위. 모르면 막는다. */
    public static final String DENY_UNKNOWN_RESOURCE = "UNKNOWN_RESOURCE";

    public static AuthorizationDecision allow(Long employeeId, String role,
                                              String branchCode, String employeeName) {
        return new AuthorizationDecision(ALLOW, null, employeeId, role, branchCode, employeeName);
    }

    public static AuthorizationDecision deny(String denyCode, Long employeeId, String role,
                                             String branchCode, String employeeName) {
        return new AuthorizationDecision(DENY, denyCode, employeeId, role, branchCode, employeeName);
    }

    public boolean allowed() {
        return ALLOW.equals(decision);
    }
}
