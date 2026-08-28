package com.bank.gateway.filter;

import com.bank.common.security.jwt.JwtClaims;
import com.bank.common.security.jwt.JwtProvider;
import com.bank.common.security.jwt.TokenType;
import com.bank.common.web.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 모든 요청을 가로채 JWT 를 검증한다.
 * 공개 경로는 토큰 없이 통과시키고, 보호 경로는 유효한 ACCESS 토큰이 있어야 upstream 으로 전달한다.
 * 검증 성공 시 X-Customer-Id / X-Customer-Email 헤더를 downstream 에 추가한다.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_CUSTOMER = "ROLE_CUSTOMER";

    /** 인증 없이 통과할 경로 접두사 목록 */
    private static final List<String> PUBLIC_PATH_PREFIXES = List.of(
            "/api/v1/auth/",
            "/api/v1/mobile-auth/",
            "/actuator/"
    );

    /**
     * 고객 본인만 호출 가능한 경로(자금이동·계좌·고객 자가설정). 직원/관리자 세션 토큰은 ROLE_CUSTOMER 가 없어 차단된다.
     * 직원용 조회·처리는 {@code /api/v1/internal/**} 로 분리돼 있고, 직원의 개인 거래·자가설정은
     * 사용자 모드(ROLE_CUSTOMER) 로그인으로 정상 수행한다.
     */
    private static final List<String> CUSTOMER_ONLY_PATHS = List.of(
            "/api/v1/accounts",
            "/api/v1/payments",
            "/api/v1/customers/me",  // 고객 자가설정·인뱅해지·이체한도 등 — 직원/관리자 세션 차단(#72)
            // 아래 둘이 고객이 실제로 쓰는 경로다. 이름이 헷갈리지만 버전 없는 쪽이 고객용이고,
            // /api/v1/accounts 는 payment↔deposit 서비스 간 API 다(AccountV1Controller).
            // 처음 #72 를 넣을 때 v1 쪽만 올려서, 정작 자금이 나가는 문이 열려 있었다.
            "/api/accounts",
            "/api/transactions",
            // 이상거래 안내는 고객 본인이 자기 화면에서 부른다. 직원 토큰으로 부르면
            // 직원 자신의 연령대에 맞춰진 안내가 나와 아무 뜻이 없다.
            "/api/v1/fraud/guidance",
            "/api/v1/fraud/consult"
    );

    private final JwtProvider jwtProvider;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (isPublic(path)) {
            // 인증을 요구하지 않는 경로여도 신원 헤더는 지운다.
            //
            // 지우지 않으면 클라이언트가 X-User-Id 를 직접 실을 수 있고, 그 값은
            // 속도제한 버킷 키로 쓰인다. 요청마다 다른 값을 넣으면 버킷이 매번 새로
            // 생겨 로그인 속도제한이 통째로 우회된다 — 브루트포스를 막으려고 건
            // 한도가 정작 브루트포스에 뚫린다.
            return chain.filter(exchange.mutate()
                    .request(exchange.getRequest().mutate()
                            .headers(JwtAuthenticationFilter::stripIdentityHeaders)
                            .build())
                    .build());
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        JwtClaims claims;
        try {
            claims = jwtProvider.parseClaims(token);
        } catch (BusinessException e) {
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }

        if (claims.tokenType() != TokenType.ACCESS) {
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }

        // 자금이동·계좌 경로는 고객 본인 토큰(ROLE_CUSTOMER)만 허용 — 직원/관리자 세션의 거래 차단.
        if (requiresCustomerRole(path)
                && (claims.roles() == null || !claims.roles().contains(ROLE_CUSTOMER))) {
            return reject(exchange, HttpStatus.FORBIDDEN);
        }

        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(JwtAuthenticationFilter::stripIdentityHeaders)
                .header("X-Customer-Id",    String.valueOf(claims.customerId()))
                .header("X-Customer-Email", claims.email() != null ? claims.email() : "")
                .header("X-User-Id",        String.valueOf(claims.customerId()))
                .header("X-User-Role",      String.join(",", claims.roles()))
                .header("X-User-Branch",    claims.branch() != null ? claims.branch() : "")
                .header("X-User-Grade",     claims.grade()  != null ? claims.grade()  : "")
                .header("X-Employee-Id",    claims.employeeId() != null ? String.valueOf(claims.employeeId()) : "")
                .build();

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /**
     * 게이트웨이가 주입하는 신원 헤더를 요청에서 제거한다.
     *
     * <p>클라이언트가 보낸 같은 이름의 헤더를 먼저 지워야 뒤에서 넣는 값이 유일해진다.
     * 인증 경로든 공개 경로든 지우는 것은 같다 — 공개 경로에서 남겨 두면 그 값이
     * 속도제한 키나 하위 서비스로 흘러간다.
     */
    private static void stripIdentityHeaders(HttpHeaders h) {
        h.remove("X-Customer-Id");
        h.remove("X-Customer-Email");
        h.remove("X-User-Id");
        h.remove("X-User-Role");
        h.remove("X-User-Branch");
        h.remove("X-User-Grade");
        h.remove("X-Employee-Id");
    }

    @Override
    public int getOrder() {
        // 라우트 필터(속도제한 등)보다 먼저 돌아야 한다. 순서가 뒤집히면
        // 속도제한이 클라이언트가 보낸 신원 헤더를 그대로 키로 쓴다.
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private boolean isPublic(String path) {
        return PUBLIC_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }

    /** 경로가 고객 전용(자금이동·계좌)인지 — base 자체 또는 그 하위 경로면 true. */
    private boolean requiresCustomerRole(String path) {
        return CUSTOMER_ONLY_PATHS.stream()
                .anyMatch(base -> path.equals(base) || path.startsWith(base + "/"));
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }
}
