package com.bank.payment.security;

import com.bank.common.security.Sha256;
import com.bank.deposit.exception.ErrorCode;
import com.bank.common.security.service.DenyReason;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

/**
 * Core Banking 의 내부 API 에 호출 주체 인증을 세운다.
 *
 * <p><b>왜 필요한가.</b> {@code payment/config/SecurityConfig} 는
 * {@code anyRequest().permitAll()} 이고 그 주석은 "게이트웨이가 JWT 인증 → 백엔드는
 * 통과" 다. 그런데 <b>서비스 간 호출은 게이트웨이를 지나지 않는다.</b> 즉 지금
 * core-banking 의 내부 호출에는 인증이 없다.
 *
 * <p>근거: {@code docs/decisions/transaction-initiator-auth-model.md} §7 —
 * 최종 보안 모델은 모든 Core Banking internal API 에 인증을 요구한다.
 *
 * <p><b>지금은 거절하지 않는다.</b> 승인 게이트를 켤 때 경로 목록을 사람이 주석에
 * 적고 켰다가 여신 경로 넷을 빠뜨린 일이 있었다. 같은 실수를 반복하지 않으려면
 * 목록을 데이터로 확인해야 한다 — 미인증 호출을 감사에 남겨 누가 인증 없이
 * 들어오는지 먼저 본다.
 *
 * <p><b>이 허용은 최종 보안 정책이 아니라 전환 절차다.</b> 설정 이름이 그 상태를
 * 드러내도록 {@code enforce} 로 두었다. 미인증 호출이 0 이 되면 켠다.
 *
 * <p><b>{@code @Component} 가 아닌 이유.</b> 슬라이스 테스트({@code @WebMvcTest})는
 * 필터 빈을 함께 등록하는데, 슬라이스에는 SqlSessionFactory 가 없어 매퍼 주입이
 * 실패하고 컨텍스트가 통째로 죽는다 — 실제로 수신계 컨트롤러 테스트 13개가 그렇게
 * 넘어졌다. {@code MyBatisMapperConfig} 가 같은 이유로 분리돼 있다.
 * 등록은 {@link ServiceCredentialFilterConfig} 가 한다.
 */
@Slf4j
public class ServiceCredentialFilter extends OncePerRequestFilter {

    /** 서비스 신원이 이 요청 처리 동안 놓이는 자리. */
    public static final String SERVICE_ID_ATTRIBUTE = "core-banking.serviceId";

    private static final String INTERNAL_PATH_PREFIX = "/api/v1/internal/";
    private static final String CREDENTIAL_HEADER = "X-Internal-Token";

    /**
     * 직원이 부르는 운영 경로. 서비스 신원이 아니라 직원 신원으로 인가한다.
     *
     * <p>확인되지 않은 호출자를 위해 {@code OPERATOR} 라는 서비스 신원을 만들면
     * 그 신원이 곧 우회로가 된다. 서비스가 아닌 것을 서비스로 등록하지 않는다.
     */
    private static final String EMPLOYEE_OPERATED_PREFIX = "/api/v1/internal/reconciliation/";

    private final ServiceAuthorizationMapper mapper;
    private final ServiceAuthorizationAuditWriter auditWriter;
    private final boolean enforce;

    public ServiceCredentialFilter(ServiceAuthorizationMapper mapper,
                                   ServiceAuthorizationAuditWriter auditWriter,
                                   @Value("${payment.internal-auth.enforce:false}") boolean enforce) {
        this.mapper = mapper;
        this.auditWriter = auditWriter;
        this.enforce = enforce;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null
                || !path.startsWith(INTERNAL_PATH_PREFIX)
                // 직원 운영 경로는 이 필터의 대상이 아니다. 다른 주체 모델을 쓴다.
                || path.startsWith(EMPLOYEE_OPERATED_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String credential = request.getHeader(CREDENTIAL_HEADER);
        ServicePrincipal principal = resolve(credential);

        if (principal != null && principal.isActive()) {
            // 신원이 섰다. 작업 인가는 각 컨트롤러가 자기 작업으로 판정한다 —
            // 여기서 할 수 없는 판단이다.
            request.setAttribute(SERVICE_ID_ATTRIBUTE, principal.serviceId());
            chain.doFilter(request, response);
            return;
        }

        // 신원을 세우지 못했다. 이 기록이 전환 절차의 입력이다 — 누가 인증 없이
        // 들어오는지를 사람이 짐작하지 않고 데이터로 확인한다.
        DenyReason reason = principal == null
                ? DenyReason.UNKNOWN_CREDENTIAL
                : DenyReason.SERVICE_SUSPENDED;
        auditWriter.write(new ServiceAuthorizationLogEntry(
                principal == null ? null : principal.serviceId(),
                null,
                ServiceAuthorizationLogEntry.DECISION_DENY,
                reason.name(),
                request.getHeader("X-Idempotency-Key"),
                null, null,
                MDC.get("traceId"),
                request.getRequestURI()));

        if (enforce) {
            reject(response);
            return;
        }

        // 전환 단계 — 아직 막지 않는다. 경고는 남긴다. 조용히 통과시키면 이 상태가
        // 언제 끝나는지 아무도 모르게 된다.
        log.warn("인증 없는 내부 호출 (전환 단계라 통과시킨다) path={} reason={}",
                request.getRequestURI(), reason);
        chain.doFilter(request, response);
    }

    private ServicePrincipal resolve(String credential) {
        if (credential == null || credential.isBlank()) {
            return null;
        }
        return mapper.findPrincipalByCredentialHash(Sha256.hex(credential));
    }

    /**
     * 거절 응답을 직접 쓴다.
     *
     * <p>예외를 던지지 않는 이유가 있다. 필터에서 던진 예외는 스프링 MVC 의 예외
     * 핸들러를 지나지 않아 500 으로 나간다 — 실제로 그랬다. 인가 거절이 500 이면
     * 호출부는 서버 장애로 읽고, 감시도 장애로 센다. 막은 것과 고장난 것은 다른
     * 사건이라 다르게 보여야 한다.
     *
     * <p>본문은 컨트롤러 거절({@code BusinessException(FORBIDDEN)})과 같은 모양으로
     * 맞춘다. 같은 이유로 막혔는데 응답 형태가 다르면 호출부가 두 번 처리해야 한다.
     */
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(ErrorCode.FORBIDDEN.getStatus().value());
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("""
                {"code":"FORBIDDEN","message":"%s","timestamp":"%s"}"""
                .formatted(ErrorCode.FORBIDDEN.getMessage(), OffsetDateTime.now()));
    }
}
