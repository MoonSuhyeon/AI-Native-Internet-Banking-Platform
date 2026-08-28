package com.bank.deposit.audit;

import com.bank.deposit.client.CustomerServiceClient;
import com.bank.deposit.client.dto.EmployeeAuthorizationRequest;
import com.bank.deposit.client.dto.EmployeeAuthorizationResponse;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 수신 자원 접근의 <b>최종 경계</b>.
 *
 * <p>경계가 둘인 것이 핵심이다.
 * <pre>
 *   ① customer-service — 직원 신원·역할의 정본. "이 직원이 이런 일을 해도 되는가"
 *   ② core-banking(여기) — 자기 자원의 최종 경계. "이 자원을 내줘도 되는가"
 * </pre>
 *
 * <p><b>①만으로 끝내지 않는 이유.</b> core-banking 이 "중앙이 허용했다니까 준다" 가 되면,
 * 중앙 인가가 잘못 열리거나 뚫렸을 때 자원이 그대로 따라 열린다. 자원을 가진 쪽이
 * 자기 기준을 따로 갖고 있어야 한 겹이 뚫려도 다음 겹이 남는다.
 *
 * <p><b>부르는 쪽의 주장을 믿지 않는다.</b> 상담이 "나는 허가받았다" 는 헤더를 실어
 * 보내는 방식이면 그것은 클라이언트가 주장하는 값이다. 그래서 여기서 직접 묻는다.
 *
 * <p><b>인가 서비스가 안 되면 막는다(fail-closed).</b> 판단을 못 받았는데 통과시키면,
 * 인가 서비스 장애가 곧 전면 개방이 된다. 이 레포의 FDS 게이트도 같은 원칙이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResourceAccessGuard {

    private static final String CALLER = "core-banking";

    private final CustomerServiceClient customerServiceClient;
    private final AccessAuditRecorder auditRecorder;

    /**
     * 조회를 허용할지 결정하고, 요청·판단·결과를 한 건으로 기록한다.
     *
     * @throws BusinessException 거절된 경우. 거절은 던지기 <b>전에</b> 기록된다
     */
    public void authorizeRead(AccessActor actor, String resource, String action,
                              String targetCustomerId) {
        String upstreamDecision = null;
        String upstreamDenyCode = null;

        // ── 경계 ① 직원이면 신원·역할을 정본에 묻는다 ─────────────────────
        if (actor.isEmployee()) {
            EmployeeAuthorizationResponse authz = askAuthorization(actor, resource, action, targetCustomerId);
            if (authz == null) {
                record(actor, resource, action, targetCustomerId, null, null,
                        AccessAuditRecorder.DENIED, "인가 판단 불가(fail-closed)");
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "인가 판단을 받을 수 없어 조회를 중단했습니다.");
            }
            upstreamDecision = authz.decision();
            upstreamDenyCode = authz.denyCode();

            if (!authz.allowed()) {
                record(actor, resource, action, targetCustomerId, upstreamDecision, upstreamDenyCode,
                        AccessAuditRecorder.DENIED, "인가 거절: " + authz.denyCode());
                throw new BusinessException(ErrorCode.FORBIDDEN, "이 자원을 조회할 권한이 없습니다.");
            }
        }

        // ── 경계 ② core-banking 자기 기준 ────────────────────────────────
        // 상류가 ALLOW 여도 여기서 다시 본다. 이 검사가 없으면 ①이 곧 최종 경계가 된다.
        String resourceDenial = checkResourceRules(actor, resource, targetCustomerId);
        if (resourceDenial != null) {
            record(actor, resource, action, targetCustomerId, upstreamDecision, upstreamDenyCode,
                    AccessAuditRecorder.DENIED, resourceDenial);
            throw new BusinessException(ErrorCode.FORBIDDEN, resourceDenial);
        }

        record(actor, resource, action, targetCustomerId, upstreamDecision, upstreamDenyCode,
                AccessAuditRecorder.ALLOWED, null);
    }

    /**
     * core-banking 이 자기 자원에 대해 스스로 판단하는 규칙.
     *
     * <p>여기서 직원 역할을 다시 계산하지는 않는다 — 그 정본은 customer-service 다.
     * 대신 <b>자원 쪽에서만 알 수 있는 것</b>을 본다.
     *
     * @return 거절 사유. 통과면 {@code null}
     */
    private String checkResourceRules(AccessActor actor, String resource, String targetCustomerId) {
        if (targetCustomerId == null || targetCustomerId.isBlank()) {
            return "조회 대상이 지정되지 않았습니다.";
        }
        // 고객 행위자는 자기 것만. 상류 판단과 무관하게 자원 쪽에서 막는다.
        if (AccessActor.CUSTOMER.equals(actor.type())
                && !targetCustomerId.equals(actor.customerId())) {
            return "다른 고객의 데이터에는 접근할 수 없습니다.";
        }
        // 서비스 행위자는 대리 조회 주체를 밝혀야 한다.
        if (AccessActor.SERVICE.equals(actor.type()) && actor.lacksReason()) {
            return "서비스 대리 조회에는 사유가 필요합니다.";
        }
        return null;
    }

    private EmployeeAuthorizationResponse askAuthorization(AccessActor actor, String resource,
                                                          String action, String targetCustomerId) {
        try {
            return customerServiceClient.authorizeEmployee(
                    new EmployeeAuthorizationRequest(
                            actor.employeeId(), resource, action, targetCustomerId, actor.reason()),
                    CALLER);
        } catch (Exception e) {
            // 판단을 못 받았다. 통과시키면 인가 서비스 장애가 전면 개방이 된다.
            log.error("인가 판단 호출 실패 — employeeId={} resource={} action={}",
                    actor.employeeId(), resource, action, e);
            return null;
        }
    }

    private void record(AccessActor actor, String resource, String action, String targetCustomerId,
                        String authzDecision, String authzDenyCode,
                        String result, String deniedReason) {
        auditRecorder.record(actor, resource, action, targetCustomerId, null,
                authzDecision, authzDenyCode, result, deniedReason);
    }
}
