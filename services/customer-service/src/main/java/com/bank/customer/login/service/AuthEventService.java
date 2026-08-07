package com.bank.customer.login.service;

import com.bank.common.security.BankRole;
import com.bank.common.security.Sha256;
import com.bank.common.security.jwt.JwtProperties;
import com.bank.common.security.jwt.JwtProvider;
import com.bank.common.web.BusinessException;
import com.bank.customer.customer.domain.Customer;
import com.bank.customer.fds.domain.FdsDetection;
import com.bank.customer.fds.service.FdsService;
import com.bank.customer.metrics.AuthMetrics;
import com.bank.customer.login.domain.LoginAttempt;
import com.bank.customer.login.dto.LoginResponse;
import com.bank.customer.login.repository.LoginAttemptRepository;
import com.bank.customer.party.service.EmployeeDirectoryService;
import com.bank.customer.session.service.LoginSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 모든 로그인 경로(비밀번호·PIN·인증서·QR)의 공통 후처리.
 *
 * <p>각 로그인 서비스는 인증 검증만 수행하고, 성공/실패 후처리는 이 컴포넌트에 위임한다:
 * <ul>
 *   <li>{@code login_attempt} — 로그인 시도 감사 이력(성공/실패 공통)</li>
 *   <li>access/refresh 토큰 발급 + Redis(RT:) 해시 저장</li>
 *   <li>{@code login_session} + {@code api_token} — 세션·토큰 DB 이력</li>
 *   <li>FDS({@code LOGIN_ATTEMPT}) 평가</li>
 * </ul>
 *
 * <p>인증수단 고유 이력(예: 인증서 {@code certificate_use}, {@code CERT_LOGIN} FDS)은
 * 각 서비스가 별도로 남긴다 — 이 컴포넌트는 인증수단과 무관한 공통 부분만 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthEventService {

    public static final String CHANNEL_WEB    = "WEB";
    public static final String CHANNEL_MOBILE = "MOBILE";

    private static final String RT_KEY_PREFIX = "RT:";

    private final LoginAttemptRepository   loginAttemptRepository;
    private final LoginSessionService      loginSessionService;
    private final FdsService               fdsService;
    private final JwtProvider              jwtProvider;
    private final JwtProperties            jwtProperties;
    private final StringRedisTemplate      redisTemplate;
    private final EmployeeDirectoryService employeeDirectory;
    private final AuthMetrics              authMetrics;

    /**
     * 로그인 성공 후처리. 호출자 트랜잭션에 참여한다.
     * attempt 저장 → 토큰 발급 → Redis 저장 → 세션·토큰 이력 → FDS(성공 경로, silent).
     *
     * @param loginId  attempt 에 기록할 식별자. 인증서처럼 로그인ID가 없으면 일련번호를 넘긴다.
     */
    @Transactional
    public LoginResponse onLoginSuccess(Customer customer, String loginId,
                                        String ip, String userAgent, String channel) {
        LoginAttempt attempt = saveAttempt(loginId, customer.getCustomerId(), ip, userAgent, channel, true, null);

        String accessToken  = buildAccessToken(customer);
        String refreshToken = jwtProvider.generateRefreshToken(customer.getCustomerId());
        storeRefreshToken(customer.getCustomerId(), refreshToken);

        loginSessionService.createSession(
                customer.getCustomerId(), attempt.getLoginAttemptId(), ip,
                accessToken, refreshToken,
                OffsetDateTime.now().plusSeconds(jwtProperties.accessTokenValidity()  / 1000),
                OffsetDateTime.now().plusSeconds(jwtProperties.refreshTokenValidity() / 1000));

        evaluateFdsSilently(customer.getCustomerId(), attempt.getLoginAttemptId());

        authMetrics.loginSuccess();
        return new LoginResponse(customer.getCustomerId(), accessToken, refreshToken);
    }

    /**
     * 로그인 실패 후처리. attempt 저장 후 {@code evaluateFds=true} 면 FDS 평가.
     * BLOCK 룰이 발동하면 BusinessException(CUST_060)을 그대로 전파한다.
     *
     * <p>인증서처럼 인증수단 고유 FDS(CERT_LOGIN)를 이미 평가한 경로는
     * {@code evaluateFds=false} 로 호출해 중복 평가를 피한다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public void onLoginFailure(String loginId, Long customerId, String ip, String userAgent,
                               String channel, String failureCode, boolean evaluateFds) {
        LoginAttempt attempt = saveAttempt(loginId, customerId, ip, userAgent, channel, false, failureCode);
        authMetrics.loginFailure(loginFailureReason(failureCode));
        if (evaluateFds && customerId != null) {
            fdsService.evaluate(customerId, FdsDetection.EVENT_LOGIN_ATTEMPT, attempt.getLoginAttemptId());
        }
    }

    /**
     * 로그인 실패 에러코드를 알림 집계용 저카디널리티 reason 라벨로 매핑한다.
     * {@code bad_credentials} 는 alerts.yml 의 BruteForceLoginDetected 가 집계하는 대상.
     */
    private static String loginFailureReason(String failureCode) {
        if (failureCode == null) return "unknown";
        return switch (failureCode) {
            case "CUST_010" -> "bad_credentials";   // 아이디/비밀번호 불일치
            case "CUST_011" -> "locked";            // 계정 잠금
            case "CUST_012" -> "inactive";          // 탈퇴·비활성
            case "CUST_013" -> "password_expired";  // 비밀번호 만료
            case "CUST_082" -> "bad_pin";           // PIN 불일치
            case "CUST_083" -> "pin_locked";        // PIN 잠금
            case "CUST_060" -> "fds_blocked";       // FDS 차단
            default         -> "other";
        };
    }

    /**
     * 토큰 재발급(refresh). 로그인 시도/세션 이력은 남기지 않고 토큰만 새로 발급한다.
     */
    public LoginResponse reissueTokens(Customer customer) {
        String accessToken  = buildAccessToken(customer);
        String refreshToken = jwtProvider.generateRefreshToken(customer.getCustomerId());
        storeRefreshToken(customer.getCustomerId(), refreshToken);
        return new LoginResponse(customer.getCustomerId(), accessToken, refreshToken);
    }

    // -------------------------------------------------------------------------
    // 내부 헬퍼
    // -------------------------------------------------------------------------

    private LoginAttempt saveAttempt(String loginId, Long customerId, String ip, String userAgent,
                                     String channel, boolean success, String failureCode) {
        return loginAttemptRepository.save(LoginAttempt.builder()
                .attemptedLoginId(loginId)
                .customerId(customerId)
                .loginAttemptChannelCode(channel)
                .loginAttemptIp(ip)
                .loginAttemptUserAgent(userAgent)
                .loginAttemptSuccessYn(success)
                .loginAttemptFailureReasonCode(failureCode)
                .loginAttemptedAt(OffsetDateTime.now())
                .build());
    }

    /** 직원이면 직급·지점 claim 포함, 일반 고객이면 ROLE_CUSTOMER 단일 권한으로 access token 발급. */
    private String buildAccessToken(Customer customer) {
        return employeeDirectory.findByPartyId(customer.getPartyId())
                .map(emp -> jwtProvider.generateAccessToken(
                        customer.getCustomerId(), customer.getEmail(),
                        emp.roles(), emp.branch(), emp.grade(), emp.employeeId()))
                .orElseGet(() -> jwtProvider.generateAccessToken(
                        customer.getCustomerId(), customer.getEmail(),
                        List.of(BankRole.CUSTOMER.authority())));
    }

    private void storeRefreshToken(Long customerId, String refreshToken) {
        redisTemplate.opsForValue().set(
                RT_KEY_PREFIX + customerId,
                Sha256.hex(refreshToken),
                Duration.ofMillis(jwtProperties.refreshTokenValidity()));
    }

    /** 성공 경로 FDS 평가. BLOCK 룰이 발동해도 로그인은 막지 않고 로그만 남긴다(모니터링). */
    private void evaluateFdsSilently(Long customerId, Long referenceId) {
        try {
            fdsService.evaluate(customerId, FdsDetection.EVENT_LOGIN_ATTEMPT, referenceId);
        } catch (BusinessException e) {
            log.warn("FDS BLOCK 발동 (로그인 성공 경로): customerId={}", customerId);
        }
    }
}
