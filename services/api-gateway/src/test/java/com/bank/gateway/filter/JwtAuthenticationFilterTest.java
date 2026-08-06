package com.bank.gateway.filter;

import com.bank.common.security.jwt.JwtClaims;
import com.bank.common.security.jwt.JwtProvider;
import com.bank.common.security.jwt.TokenType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtProvider jwtProvider;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtProvider);
    }

    @Test
    @DisplayName("branch claim 없는 토큰 — 인바운드 X-User-Branch 위조 헤더가 빈 값으로 덮어씌워진다")
    void forgedBranchHeader_isOverwritten_whenTokenHasNoBranchClaim() {
        JwtClaims claims = new JwtClaims(1L, "emp@bank.com",
                List.of("ROLE_BRANCH_MANAGER"), TokenType.ACCESS, null, null, 101L);
        when(jwtProvider.parseClaims(anyString())).thenReturn(claims);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/loans")
                        .header("Authorization", "Bearer token123")
                        .header("X-User-Branch", "FORGED-BRANCH-001")
                        .header("X-User-Grade",  "FORGED-GRADE")
                        .build());

        AtomicReference<ServerHttpRequest> captured = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            captured.set(ex.getRequest());
            return Mono.empty();
        }).block();

        assertThat(captured.get().getHeaders().getFirst("X-User-Branch")).isEqualTo("");
        assertThat(captured.get().getHeaders().getFirst("X-User-Grade")).isEqualTo("");
        assertThat(captured.get().getHeaders().getFirst("X-User-Id")).isEqualTo("1");
        assertThat(captured.get().getHeaders().getFirst("X-Employee-Id")).isEqualTo("101");
    }

    @Test
    @DisplayName("branch claim 있는 토큰 — JWT claim 값이 전달된다 (인바운드 위조 무시)")
    void branchFromJwt_isForwarded_regardless_ofForgedHeader() {
        JwtClaims claims = new JwtClaims(2L, "mgr@bank.com",
                List.of("ROLE_BRANCH_MANAGER"), TokenType.ACCESS, "BR-001", "M2", 102L);
        when(jwtProvider.parseClaims(anyString())).thenReturn(claims);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/loans")
                        .header("Authorization", "Bearer token123")
                        .header("X-User-Branch", "FORGED-BRANCH-999")
                        .build());

        AtomicReference<ServerHttpRequest> captured = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            captured.set(ex.getRequest());
            return Mono.empty();
        }).block();

        assertThat(captured.get().getHeaders().getFirst("X-User-Branch")).isEqualTo("BR-001");
        assertThat(captured.get().getHeaders().getFirst("X-User-Grade")).isEqualTo("M2");
        assertThat(captured.get().getHeaders().getFirst("X-Employee-Id")).isEqualTo("102");
    }

    @Test
    @DisplayName("X-Customer-Id 위조 헤더도 JWT claim 으로 덮어씌워진다")
    void forgedCustomerIdHeader_isOverwritten() {
        JwtClaims claims = new JwtClaims(3L, "user@bank.com",
                List.of("ROLE_CUSTOMER"), TokenType.ACCESS, null, null, null);
        when(jwtProvider.parseClaims(anyString())).thenReturn(claims);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/loans")
                        .header("Authorization", "Bearer token123")
                        .header("X-Customer-Id", "99999")
                        .header("X-User-Id",     "99999")
                        .build());

        AtomicReference<ServerHttpRequest> captured = new AtomicReference<>();
        filter.filter(exchange, ex -> {
            captured.set(ex.getRequest());
            return Mono.empty();
        }).block();

        assertThat(captured.get().getHeaders().get("X-Customer-Id")).containsExactly("3");
        assertThat(captured.get().getHeaders().get("X-User-Id")).containsExactly("3");
        assertThat(captured.get().getHeaders().getFirst("X-Employee-Id")).isEqualTo("");
    }

    @Test
    @DisplayName("공개 경로는 인바운드 헤더를 그대로 통과시킨다 (JWT 검증 없음)")
    void publicPath_passesThrough_withoutModification() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login").build());

        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        filter.filter(exchange, ex -> {
            chainCalled.set(true);
            return Mono.empty();
        }).block();

        assertThat(chainCalled.get()).isTrue();
    }

    // 고객이 실제로 쓰는 경로는 버전 없는 쪽이다(/api/accounts·/api/transactions).
    // #72 를 처음 넣을 때 /api/v1/accounts(=payment↔deposit 서비스 간 API)만 올려서,
    // 정작 자금이 나가는 문에는 가드가 걸려 있지 않았다.
    @ParameterizedTest
    @ValueSource(strings = {"/api/transactions/transfer", "/api/transactions/withdraw", "/api/accounts/1/limits"})
    @DisplayName("직원/관리자 토큰은 고객용 계좌·거래 경로에서도 403 차단된다")
    void employeeToken_isBlocked_onCustomerFacingPaths(String path) {
        JwtClaims claims = new JwtClaims(8L, "teller@bank.com",
                List.of("ROLE_TELLER"), TokenType.ACCESS, "BR-001", "T1", 108L);
        when(jwtProvider.parseClaims(anyString())).thenReturn(claims);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(path)
                        .header("Authorization", "Bearer token123")
                        .build());

        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        filter.filter(exchange, ex -> {
            chainCalled.set(true);
            return Mono.empty();
        }).block();

        assertThat(chainCalled.get()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("고객 토큰은 고객용 계좌·거래 경로를 정상 통과한다")
    void customerToken_passes_onCustomerFacingPaths() {
        JwtClaims claims = new JwtClaims(9L, "user@bank.com",
                List.of("ROLE_CUSTOMER"), TokenType.ACCESS, null, null, null);
        when(jwtProvider.parseClaims(anyString())).thenReturn(claims);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/transactions/transfer")
                        .header("Authorization", "Bearer token123")
                        .build());

        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        filter.filter(exchange, ex -> {
            chainCalled.set(true);
            return Mono.empty();
        }).block();

        assertThat(chainCalled.get()).isTrue();
    }

    @Test
    @DisplayName("직원/관리자 토큰(ROLE_CUSTOMER 없음)은 자금이동 경로(/api/v1/payments)에서 403 차단된다")
    void employeeToken_isBlocked_onPaymentPath() {
        JwtClaims claims = new JwtClaims(8L, "teller@bank.com",
                List.of("ROLE_TELLER"), TokenType.ACCESS, "BR-001", "T1", 108L);
        when(jwtProvider.parseClaims(anyString())).thenReturn(claims);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/payments/transfer")
                        .header("Authorization", "Bearer token123")
                        .build());

        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        filter.filter(exchange, ex -> {
            chainCalled.set(true);
            return Mono.empty();
        }).block();

        assertThat(chainCalled.get()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("직원/관리자 토큰은 계좌 경로(/api/v1/accounts)에서도 403 차단된다")
    void employeeToken_isBlocked_onAccountPath() {
        JwtClaims claims = new JwtClaims(9L, "admin@bank.com",
                List.of("ROLE_ADMIN"), TokenType.ACCESS, null, null, null);
        when(jwtProvider.parseClaims(anyString())).thenReturn(claims);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/accounts")
                        .header("Authorization", "Bearer token123")
                        .build());

        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        filter.filter(exchange, ex -> {
            chainCalled.set(true);
            return Mono.empty();
        }).block();

        assertThat(chainCalled.get()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("직원/관리자 토큰은 고객 자가설정 경로(/api/v1/customers/me)에서 403 차단된다 (#72)")
    void employeeToken_isBlocked_onCustomerSelfServicePath() {
        JwtClaims claims = new JwtClaims(8L, "teller@bank.com",
                List.of("ROLE_TELLER"), TokenType.ACCESS, "BR-001", "T1", 108L);
        when(jwtProvider.parseClaims(anyString())).thenReturn(claims);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/customers/me/withdraw")
                        .header("Authorization", "Bearer token123")
                        .build());

        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        filter.filter(exchange, ex -> {
            chainCalled.set(true);
            return Mono.empty();
        }).block();

        assertThat(chainCalled.get()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("고객 토큰(ROLE_CUSTOMER)은 고객 자가설정 경로를 정상 통과한다")
    void customerToken_passesThrough_onCustomerSelfServicePath() {
        JwtClaims claims = new JwtClaims(3L, "user@bank.com",
                List.of("ROLE_CUSTOMER"), TokenType.ACCESS, null, null, null);
        when(jwtProvider.parseClaims(anyString())).thenReturn(claims);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/customers/me/settings")
                        .header("Authorization", "Bearer token123")
                        .build());

        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        filter.filter(exchange, ex -> {
            chainCalled.set(true);
            return Mono.empty();
        }).block();

        assertThat(chainCalled.get()).isTrue();
    }

    @Test
    @DisplayName("고객 토큰(ROLE_CUSTOMER)은 자금이동 경로를 정상 통과한다")
    void customerToken_passesThrough_onPaymentPath() {
        JwtClaims claims = new JwtClaims(3L, "user@bank.com",
                List.of("ROLE_CUSTOMER"), TokenType.ACCESS, null, null, null);
        when(jwtProvider.parseClaims(anyString())).thenReturn(claims);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/payments/transfer")
                        .header("Authorization", "Bearer token123")
                        .build());

        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        filter.filter(exchange, ex -> {
            chainCalled.set(true);
            return Mono.empty();
        }).block();

        assertThat(chainCalled.get()).isTrue();
    }

    @Test
    @DisplayName("고객 전용이 아닌 경로(/api/v1/loans)는 직원 토큰도 통과한다 (가드 비대상)")
    void employeeToken_passesThrough_onNonCustomerOnlyPath() {
        JwtClaims claims = new JwtClaims(8L, "teller@bank.com",
                List.of("ROLE_TELLER"), TokenType.ACCESS, "BR-001", "T1", 108L);
        when(jwtProvider.parseClaims(anyString())).thenReturn(claims);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/loans")
                        .header("Authorization", "Bearer token123")
                        .build());

        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        filter.filter(exchange, ex -> {
            chainCalled.set(true);
            return Mono.empty();
        }).block();

        assertThat(chainCalled.get()).isTrue();
    }
}
