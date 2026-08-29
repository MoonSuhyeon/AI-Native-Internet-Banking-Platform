package com.bank.payment.security;

import com.bank.common.security.BankRole;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 직원 운영 작업의 인가.
 *
 * <p><b>서비스 인가와 무엇이 다른가.</b> 주체가 다르다. 서비스는 자격증명으로 신원을
 * 세우지만({@link ServiceAuthorizationService}), 직원은 게이트웨이가 JWT 를 확인해
 * 실어 보낸 신원과 역할로 인가한다. 근거는
 * {@code docs/decisions/transaction-initiator-auth-model.md} §7 —
 * <b>모든 내부 API 는 인증을 요구하되, 호출 주체에 따라 모델을 나눈다.</b>
 *
 * <p>확인되지 않은 호출자를 위해 {@code OPERATOR} 라는 서비스 신원을 만들지 않은
 * 이유가 이것이다. 사람이 하는 일을 서비스로 등록하면 그 신원이 곧 우회로가 된다.
 *
 * <p><b>왜 게이트웨이 헤더를 믿는가.</b> 이 경로는 사람이 부르는 것이고, 사람의
 * 인증은 게이트웨이가 JWT 로 한다. 게이트웨이는 들어오는 요청에서 신원 헤더를 지운
 * 뒤 자기가 확인한 값으로 다시 채우므로(JwtAuthenticationFilter), 여기까지 온 헤더는
 * 주장이 아니라 확인된 값이다. 다만 그 전제가 성립하려면 이 포트가 밖에서 닿지
 * 않아야 한다 — 망 경계는 {@code docs/decisions/security-zone-topology.md} 의 몫이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmployeeOperationGuard {

    public static final String EMPLOYEE_ID_HEADER = "X-Employee-Id";
    public static final String ROLE_HEADER = "X-User-Role";

    /** 신원 자체가 없다. */
    private static final String NO_IDENTITY = "NO_EMPLOYEE_IDENTITY";
    /** 신원은 있으나 역할이 모자란다. */
    private static final String INSUFFICIENT_ROLE = "INSUFFICIENT_ROLE";

    private final EmployeeOperationAuditWriter auditWriter;

    /**
     * 이 직원이 이 작업을 할 수 있는지 확인한다. 허용도 거절도 감사에 남는다.
     *
     * @param employeeId  게이트웨이가 실은 직원 식별자. 없으면 {@code null}
     * @param roles       게이트웨이가 실은 역할 목록(쉼표 결합)
     * @param operation   수행하려는 작업
     * @param parameters  무엇을 실행하는가. 감사에 그대로 남는다
     * @param requestPath 감사에 남길 경로
     * @throws BusinessException 신원이 없거나 역할이 모자랄 때
     */
    public void require(String employeeId, String roles, EmployeeOperation operation,
                        String parameters, String requestPath) {

        if (employeeId == null || employeeId.isBlank()) {
            deny(null, roles, operation, parameters, requestPath, NO_IDENTITY);
        }
        if (!hasRole(roles, operation.requiredRole())) {
            deny(employeeId, roles, operation, parameters, requestPath, INSUFFICIENT_ROLE);
        }

        auditWriter.write(new EmployeeOperationLogEntry(
                employeeId, roles, operation.code(), parameters,
                EmployeeOperationLogEntry.DECISION_ALLOW, null,
                MDC.get("traceId"), requestPath));
    }

    /**
     * 역할 목록에 필요한 역할이 있는가.
     *
     * <p>게이트웨이는 역할을 쉼표로 이어 보낸다. 부분 문자열로 비교하면
     * {@code ROLE_OPS} 를 찾을 때 {@code ROLE_OPSX} 같은 값이 걸리므로 잘라서 견준다.
     *
     * <p>{@link BankRole#authority()} 로 견주는 것에 주의한다 — 게이트웨이가 싣는
     * {@code X-User-Role} 은 {@code ROLE_} 접두를 포함한 authority 다.
     * {@link BankRole#spring()} 은 그 접두를 <b>제거한</b> 값이라 여기서 쓰면 아무것도
     * 맞지 않는다({@code hasRole()} 인자용이다).
     *
     * <p>enum 이름({@code OPS})도 받는다. 헤더를 손으로 넣는 경로가 있어서다.
     */
    private boolean hasRole(String roles, BankRole required) {
        if (roles == null || roles.isBlank()) {
            return false;
        }
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .anyMatch(r -> r.equals(required.authority()) || r.equals(required.name()));
    }

    private void deny(String employeeId, String roles, EmployeeOperation operation,
                      String parameters, String requestPath, String reason) {
        auditWriter.write(new EmployeeOperationLogEntry(
                employeeId, roles, operation.code(), parameters,
                EmployeeOperationLogEntry.DECISION_DENY, reason,
                MDC.get("traceId"), requestPath));

        log.warn("직원 운영 작업 거절 employee={} operation={} reason={}",
                employeeId, operation, reason);

        // 어느 단계에서 막혔는지 호출자에게 알리지 않는다. 신원이 없어서인지 역할이
        // 모자라서인지 알려 주면 권한 경계를 탐색하는 데 쓸 수 있다. 구분은
        // employee_operation_log 에만 남는다.
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }
}
