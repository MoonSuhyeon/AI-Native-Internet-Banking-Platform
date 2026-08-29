package com.bank.payment.security;

import com.bank.common.security.BankRole;

/**
 * 직원이 수행하는 운영 작업.
 *
 * <p>서비스 작업({@code ServiceOperation})과 나란한 어휘지만 주체가 다르다. 서비스는
 * 자격증명으로 신원을 세우고 작업 권한으로 인가하지만, 직원은 게이트웨이가 확인한
 * 신원과 역할로 인가한다. 근거는
 * {@code docs/decisions/transaction-initiator-auth-model.md} §7.
 *
 * <p>필요한 역할을 작업마다 코드로 적는다. 서비스 권한처럼 DB 정본으로 두지 않는
 * 이유는, 직원 역할 체계({@link BankRole})가 이미 있고 그것을 이 자리에서 다시
 * 데이터로 옮기면 정본이 둘이 되기 때문이다. 여기서 정하는 것은 "이 작업에 어떤
 * 역할이 필요한가" 하나다.
 */
public enum EmployeeOperation {

    /**
     * 대외 대사 실행.
     *
     * <p>운영 역할을 요구한다. 재실행이 흔한 작업이지만(장애로 배치가 건너뛴 날,
     * 대사 로직을 고친 뒤, 불일치를 조치한 뒤) 아무나 돌릴 일은 아니다 — 결과가
     * 마감 판단의 근거가 되기 때문이다.
     */
    RECONCILIATION_RUN(BankRole.OPS);

    private final BankRole requiredRole;

    EmployeeOperation(BankRole requiredRole) {
        this.requiredRole = requiredRole;
    }

    public BankRole requiredRole() {
        return requiredRole;
    }

    public String code() {
        return name();
    }
}
