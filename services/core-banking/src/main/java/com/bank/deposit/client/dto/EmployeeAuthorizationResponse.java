package com.bank.deposit.client.dto;

/**
 * customer-service 인가 판단 결과.
 *
 * <p>거절도 200 으로 온다. 부르는 쪽이 거절 사유와 행위자 스냅샷을 감사에 남겨야
 * 하기 때문이다.
 */
public record EmployeeAuthorizationResponse(
        String decision,
        String denyCode,
        Long employeeId,
        String role,
        String branchCode,
        String employeeName
) {
    public boolean allowed() {
        return "ALLOW".equals(decision);
    }
}
