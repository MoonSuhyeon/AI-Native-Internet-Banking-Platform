package com.bank.payment.security;

import com.bank.common.security.Sha256;
import com.bank.common.security.service.AuthorizationDecision;
import com.bank.common.security.service.DenyReason;
import com.bank.common.security.service.ServiceOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 서비스 간 호출의 신원 확인과 작업 인가.
 *
 * <p><b>왜 core-banking 에 있는가.</b> {@code payment/config/SecurityConfig} 는
 * {@code anyRequest().permitAll()} 이고 그 주석은 "게이트웨이가 JWT 인증 → 백엔드는
 * 통과" 다. 그런데 <b>서비스 간 호출(동서)은 게이트웨이를 지나지 않는다.</b> 즉 지금
 * core-banking 의 내부 호출에는 인증이 없다.
 *
 * <p>근거와 원칙은 {@code docs/decisions/transaction-initiator-auth-model.md} —
 * Core Banking 을 최초의 내부 보안 경계로 세우고, 게이트웨이의 사용자 인증에
 * 의존하지 않고 여기서도 호출 주체를 검증한다.
 *
 * <p><b>판정 순서가 곧 정책이다.</b> 신원 → 상태 → 작업 → 권한 → 금액 → 계좌.
 * 앞 단계가 막히면 뒤를 보지 않는다. 그래야 거절 사유가 하나로 정해져 감사에서
 * 집계된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceAuthorizationService {

    private final ServiceAuthorizationMapper mapper;
    private final ServiceAuthorizationAuditWriter auditWriter;

    /**
     * 호출을 허용할지 판정하고, 그 판정을 감사에 남긴다.
     *
     * <p>거절을 예외로 던지지 않고 값으로 돌려주는 이유는 {@link AuthorizationDecision}
     * 에 적었다 — 판정한 곳이 기록해야 기록이 빠지지 않는다.
     *
     * @param credential      호출자가 제시한 자격증명 원문. 없으면 {@code null}
     * @param operationCode   수행하려는 작업
     * @param context         금액·계좌 등 정책 판단에 쓰는 요청 정보
     */
    public AuthorizationDecision authorize(String credential, String operationCode, RequestContext context) {
        AuthorizationDecision decision = decide(credential, operationCode, context);
        auditWriter.write(toLogEntry(decision, operationCode, context));
        return decision;
    }

    private AuthorizationDecision decide(String credential, String operationCode, RequestContext context) {

        // 1) 신원 — 자격증명에서 나온다. 호출자가 자기가 누구인지 말하는 헤더는 두지 않는다.
        //    그런 헤더를 믿으면 토큰 하나를 가진 무엇이든 다른 서비스를 사칭할 수 있다.
        if (credential == null || credential.isBlank()) {
            return AuthorizationDecision.denyUnknownCaller();
        }
        ServicePrincipal principal = mapper.findPrincipalByCredentialHash(Sha256.hex(credential));
        if (principal == null) {
            return AuthorizationDecision.denyUnknownCaller();
        }

        // 2) 상태
        if (!principal.isActive()) {
            return AuthorizationDecision.deny(principal.serviceId(), null, DenyReason.SERVICE_SUSPENDED);
        }

        // 3) 작업 — 모르는 이름은 거절이다. 예외를 던지지 않는 이유는 거절 감사를
        //    남길 기회를 잃지 않기 위해서다.
        ServiceOperation operation = ServiceOperation.from(operationCode);
        if (operation == null) {
            return AuthorizationDecision.deny(principal.serviceId(), null, DenyReason.UNKNOWN_OPERATION);
        }

        // 4) 권한 — 부여된 적 없음과 회수됨을 구분한다. 감사에서 다른 사건이다.
        ServicePermission permission = mapper.findPermission(principal.serviceId(), operation.code());
        if (permission == null) {
            return AuthorizationDecision.deny(principal.serviceId(), operation, DenyReason.NO_PERMISSION);
        }
        if (!permission.isActive()) {
            return AuthorizationDecision.deny(principal.serviceId(), operation, DenyReason.PERMISSION_REVOKED);
        }

        // 5) 금액
        if (!permission.withinAmountLimit(context.amount())) {
            return AuthorizationDecision.deny(principal.serviceId(), operation, DenyReason.AMOUNT_EXCEEDED);
        }

        // 6) 계좌 — 작업 권한만 있고 계좌 제한이 없으면, 권한을 가진 서비스가 아무 고객
        //    계좌에서나 자금을 뺄 수 있다. 목록이 비어 있으면 제한하지 않는다는 뜻이다.
        List<String> allowed = mapper.findAllowedAccounts(permission.permissionId());
        if (!allowed.isEmpty() && !allowed.contains(context.senderAccountNo())) {
            return AuthorizationDecision.deny(principal.serviceId(), operation, DenyReason.ACCOUNT_NOT_ALLOWED);
        }

        return AuthorizationDecision.allow(principal.serviceId(), operation);
    }

    private ServiceAuthorizationLogEntry toLogEntry(AuthorizationDecision decision,
                                                    String requestedOperation,
                                                    RequestContext context) {
        // 요청한 작업 이름을 그대로 남긴다. 아는 값이 아니어서 거절했더라도, 무엇을
        // 부르려 했는지가 기록에 있어야 오설정인지 탐색인지 구분할 수 있다.
        return new ServiceAuthorizationLogEntry(
                decision.serviceId(),
                requestedOperation,
                decision.allowed()
                        ? ServiceAuthorizationLogEntry.DECISION_ALLOW
                        : ServiceAuthorizationLogEntry.DECISION_DENY,
                decision.denyReason() == null ? null : decision.denyReason().name(),
                context.idempotencyKey(),
                context.amount(),
                context.senderAccountNo(),
                context.traceId(),
                context.requestPath()
        );
    }

    /**
     * 정책 판단에 쓰는 요청 정보.
     *
     * <p>결제 전용 필드를 담지 않는다 — 이 구조는 deposit·customer 의 내부 호출에도
     * 같은 모양으로 쓰인다.
     */
    public record RequestContext(
            Long amount,
            String senderAccountNo,
            String idempotencyKey,
            String traceId,
            String requestPath
    ) {
    }
}
