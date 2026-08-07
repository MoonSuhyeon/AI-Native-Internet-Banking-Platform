package com.bank.customer.login.service;

import com.bank.common.security.jwt.JwtProperties;
import com.bank.common.security.jwt.JwtProvider;
import com.bank.common.web.BusinessException;
import com.bank.customer.customer.domain.Customer;
import com.bank.customer.fds.domain.FdsDetection;
import com.bank.customer.fds.service.FdsService;
import com.bank.customer.metrics.AuthMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.bank.customer.login.domain.LoginAttempt;
import com.bank.customer.login.dto.LoginResponse;
import com.bank.customer.login.repository.LoginAttemptRepository;
import com.bank.customer.party.service.EmployeeDirectoryService;
import com.bank.customer.session.service.LoginSessionService;
import com.bank.customer.support.CustomerErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 모든 로그인 경로가 공유하는 후처리 검증:
 * 성공 → 시도 이력 + 토큰 + 세션 + FDS, 실패 → 시도 이력 (+옵션 FDS).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthEventServiceTest {

    @Mock LoginAttemptRepository   loginAttemptRepository;
    @Mock LoginSessionService      loginSessionService;
    @Mock FdsService               fdsService;
    @Mock StringRedisTemplate      redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock EmployeeDirectoryService employeeDirectory;

    private JwtProvider      jwtProvider;
    private AuthEventService authEventService;

    private static final String SECRET = "test-secret-key-must-be-at-least-32-chars-long!!";

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties(SECRET, 600_000L, 3_600_000L);
        jwtProvider = new JwtProvider(jwtProperties);

        authEventService = new AuthEventService(
                loginAttemptRepository, loginSessionService, fdsService,
                jwtProvider, jwtProperties, redisTemplate, employeeDirectory,
                new AuthMetrics(new SimpleMeterRegistry()));

        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(employeeDirectory.findByPartyId(any())).willReturn(Optional.empty());

        LoginAttempt attempt = org.mockito.Mockito.mock(LoginAttempt.class);
        given(attempt.getLoginAttemptId()).willReturn(100L);
        given(loginAttemptRepository.save(any())).willReturn(attempt);
    }

    @Test
    @DisplayName("로그인 성공 — 시도 이력(T) + 세션 생성 + LOGIN_ATTEMPT FDS + 토큰 반환")
    void onLoginSuccess_recordsAll() {
        Customer customer = mockCustomer();

        LoginResponse response = authEventService.onLoginSuccess(
                customer, "user1", "127.0.0.1", "JUnit", AuthEventService.CHANNEL_WEB);

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.customerId()).isEqualTo(1L);

        ArgumentCaptor<LoginAttempt> attemptCaptor = ArgumentCaptor.forClass(LoginAttempt.class);
        verify(loginAttemptRepository).save(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getLoginAttemptSuccessYn()).isTrue();

        verify(valueOps).set(eq("RT:1"), any(), any());
        verify(loginSessionService).createSession(eq(1L), eq(100L), eq("127.0.0.1"),
                any(), any(), any(), any());
        verify(fdsService).evaluate(eq(1L), eq(FdsDetection.EVENT_LOGIN_ATTEMPT), eq(100L));
    }

    @Test
    @DisplayName("로그인 성공 — FDS BLOCK 발동해도 로그인은 막지 않는다(silent)")
    void onLoginSuccess_fdsBlockIsSilent() {
        Customer customer = mockCustomer();
        org.mockito.BDDMockito.willThrow(new BusinessException(CustomerErrorCode.CUST_060))
                .given(fdsService).evaluate(anyLong(), eq(FdsDetection.EVENT_LOGIN_ATTEMPT), anyLong());

        LoginResponse response = authEventService.onLoginSuccess(
                customer, "user1", "127.0.0.1", "JUnit", AuthEventService.CHANNEL_WEB);

        assertThat(response.accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("로그인 실패 — 시도 이력(F) 저장 + FDS 평가(evaluateFds=true)")
    void onLoginFailure_recordsAttemptAndEvaluatesFds() {
        authEventService.onLoginFailure("user1", 1L, "127.0.0.1", "JUnit",
                AuthEventService.CHANNEL_WEB, "CUST_010", true);

        ArgumentCaptor<LoginAttempt> attemptCaptor = ArgumentCaptor.forClass(LoginAttempt.class);
        verify(loginAttemptRepository).save(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getLoginAttemptSuccessYn()).isFalse();

        verify(fdsService).evaluate(eq(1L), eq(FdsDetection.EVENT_LOGIN_ATTEMPT), eq(100L));
    }

    @Test
    @DisplayName("로그인 실패 — evaluateFds=false 면 FDS 평가하지 않는다(인증서처럼 고유 FDS 이미 평가)")
    void onLoginFailure_skipsFdsWhenDisabled() {
        authEventService.onLoginFailure("CERT-SERIAL-001", 1L, "127.0.0.1", "JUnit",
                AuthEventService.CHANNEL_WEB, "CUST_033", false);

        verify(loginAttemptRepository).save(any());
        verify(fdsService, never()).evaluate(anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("로그인 실패 — customerId 가 null 이면(미존재 ID) FDS 평가하지 않는다")
    void onLoginFailure_skipsFdsWhenCustomerNull() {
        authEventService.onLoginFailure("unknown", null, "127.0.0.1", "JUnit",
                AuthEventService.CHANNEL_WEB, "CUST_010", true);

        verify(loginAttemptRepository).save(any());
        verify(fdsService, never()).evaluate(anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("BLOCK 룰 발동 시 실패 경로에서 CUST_060 을 전파한다")
    void onLoginFailure_propagatesBlock() {
        org.mockito.BDDMockito.willThrow(new BusinessException(CustomerErrorCode.CUST_060))
                .given(fdsService).evaluate(anyLong(), eq(FdsDetection.EVENT_LOGIN_ATTEMPT), anyLong());

        assertThatThrownBy(() -> authEventService.onLoginFailure("user1", 1L, "127.0.0.1", "JUnit",
                AuthEventService.CHANNEL_WEB, "CUST_010", true))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CustomerErrorCode.CUST_060));
    }

    private Customer mockCustomer() {
        Customer c = org.mockito.Mockito.mock(Customer.class);
        given(c.getCustomerId()).willReturn(1L);
        given(c.getEmail()).willReturn("test@bank.com");
        return c;
    }
}
