package com.bank.customer.authorization;

import com.bank.common.security.BankRole;
import com.bank.customer.party.domain.Employee;
import com.bank.customer.party.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 직원이 이 자원에 이 행위를 해도 되는지 판단한다.
 *
 * <p><b>이 서비스가 customer-service 에 있는 이유.</b> 직원 신원의 정본이 여기다 —
 * {@code party} → {@code party_role} → {@code employee} 계층이 전부 이 서비스 소유이고,
 * 활성 여부 판정도 이미 {@code party_role(EMPLOYEE, ACTIVE)} 를 정식 게이트로 쓴다.
 * "고객 서비스가 직원 서비스가 됐다" 가 아니라, <b>관계자(Party) 서비스가 직원이라는
 * Party 의 역할과 상태를 제공한다</b> 고 보는 것이 정확하다.
 *
 * <p><b>정책 테이블이 없는 것은 의도다.</b> 지금 스키마에는 역할과 자원·행위를 잇는
 * permission 테이블이 없다. 없는 상태에서 ABAC/RBAC 엔진을 먼저 세우면 쓰지도 않는
 * 추상을 유지하게 된다. 그래서 Phase 1 은 <b>계약을 고정</b>하고 규칙은 코드에 둔다.
 * 정책 테이블이 필요해지면 {@link #permittedRoles} 한 곳만 DB 조회로 바꾸면 된다 —
 * 부르는 쪽 계약은 그대로다.
 *
 * <p><b>모르면 막는다.</b> 정책에 없는 자원·행위는 DENY 다. 새 자원을 추가할 때
 * 정책을 같이 적게 만드는 것이 목적이다.
 */
@Service
@RequiredArgsConstructor
public class EmployeeAuthorizationService {

    /** 고객 금융정보 열람 — 사유를 요구한다. */
    private static final Set<String> CUSTOMER_FINANCIAL_RESOURCES = Set.of(
            "DEPOSIT_ACCOUNT", "DEPOSIT_TRANSACTION", "DEPOSIT_CONTRACT");

    /**
     * 자원·행위별 허용 역할. 기본은 거절이고 여기 적힌 것만 통과한다.
     *
     * <p>HQ_MARKETING 이 빠진 것은 실수가 아니다. 마케팅은 집계로 일하지
     * 개인 잔액을 열람할 일이 없다. CUSTOMER 도 직원 경로가 아니므로 빠진다.
     */
    private static final Map<String, Set<BankRole>> POLICY = Map.of(
            "DEPOSIT_ACCOUNT:READ", Set.of(
                    BankRole.TELLER, BankRole.DEPUTY_MANAGER, BankRole.BRANCH_MANAGER,
                    BankRole.HQ_REVIEWER, BankRole.HQ_RISK, BankRole.COMPLIANCE,
                    BankRole.OPS, BankRole.ADMIN),
            "DEPOSIT_TRANSACTION:READ", Set.of(
                    BankRole.TELLER, BankRole.DEPUTY_MANAGER, BankRole.BRANCH_MANAGER,
                    BankRole.HQ_REVIEWER, BankRole.HQ_RISK, BankRole.COMPLIANCE,
                    BankRole.OPS, BankRole.ADMIN),
            "DEPOSIT_CONTRACT:READ", Set.of(
                    BankRole.TELLER, BankRole.DEPUTY_MANAGER, BankRole.BRANCH_MANAGER,
                    BankRole.HQ_REVIEWER, BankRole.COMPLIANCE, BankRole.OPS, BankRole.ADMIN));

    private final EmployeeRepository employeeRepository;

    @Transactional(readOnly = true)
    public AuthorizationDecision decide(AuthorizationRequest request) {
        Optional<Employee> found = employeeRepository.findActiveByEmployeeId(request.employeeId());
        if (found.isEmpty()) {
            // 직원 자체를 못 찾았으므로 역할·지점 스냅샷이 없다.
            return AuthorizationDecision.deny(
                    AuthorizationDecision.DENY_EMPLOYEE_INACTIVE,
                    request.employeeId(), null, null, null);
        }

        Employee employee = found.get();
        String gradeCode = employee.getGradeCode();
        String branchCode = employee.getBranchCode();

        String key = request.resource() + ":" + request.action();
        Set<BankRole> allowed = permittedRoles(key);
        if (allowed == null) {
            return AuthorizationDecision.deny(
                    AuthorizationDecision.DENY_UNKNOWN_RESOURCE,
                    employee.getEmployeeId(), gradeCode, branchCode, null);
        }

        BankRole role = parseRole(gradeCode);
        if (role == null || !allowed.contains(role)) {
            return AuthorizationDecision.deny(
                    AuthorizationDecision.DENY_ROLE_NOT_PERMITTED,
                    employee.getEmployeeId(), gradeCode, branchCode, null);
        }

        if (CUSTOMER_FINANCIAL_RESOURCES.contains(request.resource()) && isBlank(request.reason())) {
            return AuthorizationDecision.deny(
                    AuthorizationDecision.DENY_REASON_REQUIRED,
                    employee.getEmployeeId(), gradeCode, branchCode, null);
        }

        return AuthorizationDecision.allow(
                employee.getEmployeeId(), gradeCode, branchCode, null);
    }

    /**
     * 정책 조회 지점. 정책 테이블이 생기면 여기만 DB 조회로 바꾼다.
     *
     * @return 허용 역할 집합. 정책에 없는 자원·행위면 {@code null}
     */
    private Set<BankRole> permittedRoles(String resourceActionKey) {
        return POLICY.get(resourceActionKey);
    }

    /** grade_code 는 BankRole 이름을 그대로 쓴다. 모르는 값이면 역할 없음으로 본다. */
    private static BankRole parseRole(String gradeCode) {
        if (gradeCode == null) {
            return null;
        }
        try {
            return BankRole.valueOf(gradeCode);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}
