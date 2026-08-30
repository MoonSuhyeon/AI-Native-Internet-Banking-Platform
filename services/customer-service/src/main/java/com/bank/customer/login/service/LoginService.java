package com.bank.customer.login.service;

import com.bank.common.security.BankRole;
import com.bank.common.security.Sha256;
import com.bank.common.security.jwt.JwtClaims;
import com.bank.common.security.jwt.JwtProvider;
import com.bank.common.security.jwt.TokenType;
import com.bank.common.web.BusinessException;
import com.bank.common.web.CommonErrorCode;
import com.bank.customer.customer.domain.Credential;
import com.bank.customer.customer.domain.Customer;
import com.bank.customer.customer.repository.CredentialRepository;
import com.bank.customer.customer.repository.CustomerRepository;
import com.bank.customer.login.dto.LoginRequest;
import com.bank.customer.login.dto.LoginResponse;
import com.bank.customer.login.dto.RefreshRequest;
import com.bank.customer.metrics.AuthMetrics;
import com.bank.customer.support.CustomerErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아이디/비밀번호 로그인. 인증 검증만 담당하고, 성공/실패 후처리(이력·토큰·세션·FDS)는
 * {@link AuthEventService} 에 위임한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginService {

    private static final String RT_KEY_PREFIX = "RT:";

    private final CredentialRepository credentialRepository;
    private final CustomerRepository   customerRepository;
    private final PasswordEncoder      passwordEncoder;
    private final JwtProvider          jwtProvider;
    private final StringRedisTemplate  redisTemplate;
    private final AuthEventService     authEventService;
    private final AuthMetrics          authMetrics;

    /**
     * 아이디/비밀번호 로그인.
     * noRollbackFor: 실패 카운트·잠금 상태와 감사 로그는 예외 시에도 반드시 커밋돼야 한다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public LoginResponse login(LoginRequest request, String ip, String userAgent) {
        Long customerId = null;

        try {
            Credential credential = credentialRepository
                    .findByLoginIdAndDeletedAtIsNull(request.loginId())
                    .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_010));

            customerId = credential.getCustomerId();

            if (credential.isLocked()) {
                throw new BusinessException(CustomerErrorCode.CUST_011);
            }
            if (!credential.isActive()) {
                throw new BusinessException(CustomerErrorCode.CUST_012);
            }
            if (credential.isPasswordExpired()) {
                throw new BusinessException(CustomerErrorCode.CUST_013);
            }

            if (!passwordEncoder.matches(request.password(), credential.getPasswordHash())) {
                credential.recordLoginFailure();
                throw new BusinessException(
                        credential.isLocked() ? CustomerErrorCode.CUST_011 : CustomerErrorCode.CUST_010);
            }

            Customer customer = customerRepository
                    .findByCustomerIdAndDeletedAtIsNull(credential.getCustomerId())
                    .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_002));

            if (!customer.isActive()) {
                throw new BusinessException(CustomerErrorCode.CUST_012);
            }

            credential.recordLoginSuccess();

            return authEventService.onLoginSuccess(
                    customer, request.loginId(), ip, userAgent, AuthEventService.CHANNEL_WEB);

        } catch (BusinessException e) {
            // 실패 시도 이력 + FDS 평가 — BLOCK 룰이 발동하면 CUST_060 으로 재던짐
            authEventService.onLoginFailure(request.loginId(), customerId, ip, userAgent,
                    AuthEventService.CHANNEL_WEB, e.getErrorCode().getCode(), true);
            throw e;
        }
    }

    @Transactional
    /**
     * 직원 전용 로그인. **고객 계정에는 토큰을 내주지 않는다.**
     *
     * <p>관리자 화면은 이 문으로만 들어온다. 고객 로그인과 경로를 나눈 이유는,
     * 화면에서 걸러내면 고객도 일단 토큰을 받기 때문이다 — 그 토큰으로 관리자
     * API 를 부르면 어차피 거절되지만, 발급 자체를 하지 않는 편이 낫다.
     *
     * <p>인증은 통과했으나 직원이 아닌 경우다. 그래서 401 이 아니라 403 이다.
     * 이때 이미 발급된 refresh 토큰은 돌려주지 않으며 스스로 만료된다.
     */
    public LoginResponse employeeLogin(LoginRequest request, String ip, String userAgent) {
        LoginResponse response = login(request, ip, userAgent);

        JwtClaims claims = jwtProvider.parseClaims(response.accessToken());
        boolean employee = claims.roles().stream()
                .anyMatch(role -> !BankRole.CUSTOMER.authority().equals(role));
        if (!employee) {
            throw new BusinessException(CommonErrorCode.COMMON_403);
        }
        return response;
    }

    public LoginResponse refresh(RefreshRequest request) {
        JwtClaims claims;
        try {
            claims = jwtProvider.parseClaims(request.refreshToken());
        } catch (BusinessException e) {
            authMetrics.jwtInvalid();
            throw new BusinessException(CommonErrorCode.TOKEN_INVALID);
        }

        if (claims.tokenType() != TokenType.REFRESH) {
            authMetrics.jwtInvalid();
            throw new BusinessException(CommonErrorCode.TOKEN_INVALID);
        }

        Long customerId = claims.customerId();
        String key      = RT_KEY_PREFIX + customerId;
        String stored   = redisTemplate.opsForValue().get(key);

        if (stored == null || !stored.equals(Sha256.hex(request.refreshToken()))) {
            authMetrics.jwtInvalid();
            redisTemplate.delete(key);
            throw new BusinessException(CommonErrorCode.TOKEN_INVALID);
        }

        redisTemplate.delete(key);

        Customer customer = customerRepository
                .findByCustomerIdAndDeletedAtIsNull(customerId)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_002));

        return authEventService.reissueTokens(customer);
    }
}
