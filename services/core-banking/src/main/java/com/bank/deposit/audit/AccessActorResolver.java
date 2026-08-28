package com.bank.deposit.audit;

import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 요청 헤더에서 행위자를 확정하고, 자격이 없으면 <b>거절을 기록한 뒤</b> 막는다.
 *
 * <p>거절 기록이 먼저다. 예외를 먼저 던지면 그 시도는 어디에도 남지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AccessActorResolver {

    public static final String EMPLOYEE_ID_HEADER = "X-Employee-Id";
    public static final String CUSTOMER_ID_HEADER = "X-Customer-Id";
    public static final String SERVICE_HEADER     = "X-Internal-Service";
    public static final String REASON_HEADER      = "X-Access-Reason";
    public static final String TRACE_HEADER       = "X-Trace-Id";

    private final AccessAuditRecorder recorder;

    /**
     * 내부 읽기 API 의 행위자를 정한다.
     *
     * <p>우선순위는 직원 → 서비스 → 고객이다. 직원 토큰으로 들어온 요청을
     * 고객 본인 조회로 낮춰 보면 사유 요구가 빠져나간다.
     */
    public AccessActor resolve(String employeeId, String customerId, String service,
                               String reason, String traceId,
                               String action, String targetCustomerId) {
        if (isPresent(employeeId)) {
            AccessActor actor = new AccessActor(AccessActor.EMPLOYEE, Long.valueOf(employeeId.trim()),
                    null, null, trimToNull(reason), trimToNull(traceId));
            if (actor.lacksReason()) {
                recorder.denied(actor, action, targetCustomerId, null, "조회 사유 미기재");
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "직원 대리 조회에는 " + REASON_HEADER + " 가 필요합니다.");
            }
            return actor;
        }
        if (isPresent(service)) {
            return new AccessActor(AccessActor.SERVICE, null, null, service.trim(),
                    trimToNull(reason), trimToNull(traceId));
        }
        if (isPresent(customerId)) {
            return new AccessActor(AccessActor.CUSTOMER, null, customerId.trim(), null,
                    trimToNull(reason), trimToNull(traceId));
        }

        AccessActor unknown = new AccessActor("SERVICE", null, null, "unknown",
                trimToNull(reason), trimToNull(traceId));
        recorder.denied(unknown, action, targetCustomerId, null, "행위자 헤더 없음");
        throw new BusinessException(ErrorCode.FORBIDDEN, "행위자를 확인할 수 없습니다.");
    }

    /**
     * 본인 조회인지 확인한다. 고객 행위자는 자기 것만 볼 수 있다.
     * 직원·서비스는 대리 조회가 목적이므로 여기서 막지 않는다 — 대신 전부 기록된다.
     */
    public void requireOwnershipIfCustomer(AccessActor actor, String action, String targetCustomerId) {
        if (!AccessActor.CUSTOMER.equals(actor.type())) {
            return;
        }
        if (targetCustomerId != null && targetCustomerId.equals(actor.customerId())) {
            return;
        }
        recorder.denied(actor, action, targetCustomerId, null, "타인 고객 데이터 접근");
        throw new BusinessException(ErrorCode.FORBIDDEN, "다른 고객의 데이터에는 접근할 수 없습니다.");
    }

    private static boolean isPresent(String v) {
        return v != null && !v.isBlank();
    }

    private static String trimToNull(String v) {
        return isPresent(v) ? v.trim() : null;
    }
}
