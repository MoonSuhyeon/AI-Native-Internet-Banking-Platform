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
            return chain.filter(exchange);
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
                .headers(h -> {
                    h.remove("X-Customer-Id");
                    h.remove("X-Customer-Email");
                    h.remove("X-User-Id");
                    h.remove("X-User-Role");
                    h.remove("X-User-Branch");
                    h.remove("X-User-Grade");
                    h.remove("X-Employee-Id");
                })
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

    @Override
    public int getOrder() {
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
