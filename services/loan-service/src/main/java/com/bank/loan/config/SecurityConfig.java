package com.bank.loan.config;

import com.bank.common.security.jwt.JwtProvider;
import com.bank.loan.security.GatewayHeaderAuthFilter;
import com.bank.loan.security.InternalTokenFilter;
import com.bank.loan.security.JwtFallbackAuthFilter;
import com.bank.loan.security.LoanRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.List;

/**
 * loan-service Spring Security 설정.
 *
 * 필터 실행 순서:
 *   1. GatewayHeaderAuthFilter — X-User-Id / X-User-Role 헤더 읽기 → SecurityContext 에 인증 정보 등록
 *                                (API Gateway 가 JWT 검증 후 주입한 헤더를 신뢰)
 *   2. InternalTokenFilter     — Gateway 헤더 인증이 없는 경우에만 X-Internal-Token 검증 → ROLE_INTERNAL 등록
 *
 * 엔드포인트 권한 매트릭스 (LoanRole 기준):
 *   /api/internal/loan-reviews/{id}/bias-ops-note  POST  ROLE_OPS           운영팀 편향 메모
 *   /api/internal/eod/**                                 ROLE_OPS           일배치(EOD)
 *   /api/internal/**                                     ROLE_INTERNAL      서비스 간 X-Internal-Token
 *   /api/loan-reviews/{id}/bias-override           POST  ROLE_HQ_REVIEWER   이상거래 본사 담당자 우회 승인
 *   /api/loan-applications/{id}/review/escalate-to-hq POST ROLE_BRANCH_MANAGER 이상거래 본사 상신
 *   /api/loan-reviews/escalated                   GET   ROLE_HQ_REVIEWER   상신 건 목록
 *   그 외                                                authenticated       조회 스코프는 Stage 4에서 추가
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public GatewayHeaderAuthFilter gatewayHeaderAuthFilter() {
        return new GatewayHeaderAuthFilter();
    }

    @Bean
    public InternalTokenFilter internalTokenFilter(
            @Value("${internal.token}") String internalToken) {
        return new InternalTokenFilter(internalToken);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           InternalTokenFilter internalTokenFilter,
                                           @Nullable JwtProvider jwtProvider) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .addFilterBefore(gatewayHeaderAuthFilter(), UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(internalTokenFilter, GatewayHeaderAuthFilter.class);

        // jwt.secret이 설정된 경우(로컬 개발)에만 JWT 직접 파싱 폴백 필터 등록
        if (jwtProvider != null) {
            http.addFilterAfter(new JwtFallbackAuthFilter(jwtProvider), InternalTokenFilter.class);
        }

        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**"
                ).permitAll()
                .requestMatchers(
                        "/actuator/health", "/actuator/info", "/actuator/prometheus"
                ).permitAll()

                // 대출 상품 목록·상세·우대금리 조회 — 비로그인 공개
                .requestMatchers(HttpMethod.GET,
                        "/api/loan-products",
                        "/api/loan-products/*",
                        "/api/loan-products/*/preferential-rate-policies"
                ).permitAll()

                // 운영자 계약 모니터링(읽기 전용) — 운영/본사/지점 직원 역할 허용 (CUSTOMER·INTERNAL 제외)
                .requestMatchers(HttpMethod.GET,
                        "/api/admin/loan-contracts"
                ).hasAnyRole(
                        LoanRole.OPS.spring(), LoanRole.ADMIN.spring(),
                        LoanRole.HQ_REVIEWER.spring(), LoanRole.COMPLIANCE.spring(),
                        LoanRole.BRANCH_MANAGER.spring(), LoanRole.DEPUTY_MANAGER.spring()
                )

                // 운영팀 전용 내부 엔드포인트 — ROLE_OPS
                .requestMatchers(HttpMethod.POST,
                        "/api/internal/loan-reviews/*/bias-ops-note"
                ).hasRole(LoanRole.OPS.spring())
                .requestMatchers(
                        "/api/internal/eod/**"
                ).hasRole(LoanRole.OPS.spring())

                // advisory 내부 RAG 관리(문서 등록·인덱싱) — 본사 AI 공통 자산이므로
                // advisory 조회 권한(HQ 계열)과 동일하게 허용 + 서비스 간 INTERNAL
                .requestMatchers("/api/internal/advisory/**").hasAnyRole(
                        LoanRole.ADMIN.spring(), LoanRole.OPS.spring(), LoanRole.INTERNAL.spring(),
                        LoanRole.HQ_REVIEWER.spring(), LoanRole.COMPLIANCE.spring()
                )

                // 서비스 간 내부 엔드포인트 — ROLE_INTERNAL (X-Internal-Token)
                .requestMatchers("/api/internal/**").hasRole(LoanRole.INTERNAL.spring())

                // 이상거래 본사 담당자 편향 우회 승인 + 상신 건 목록 — ROLE_HQ_REVIEWER
                .requestMatchers(HttpMethod.POST,
                        "/api/loan-reviews/*/bias-override"
                ).hasRole(LoanRole.HQ_REVIEWER.spring())
                .requestMatchers(HttpMethod.GET,
                        "/api/loan-reviews/escalated"
                ).hasRole(LoanRole.HQ_REVIEWER.spring())

                // 이상거래 본사 상신 — ROLE_BRANCH_MANAGER
                .requestMatchers(HttpMethod.POST,
                        "/api/loan-applications/*/review/escalate-to-hq"
                ).hasRole(LoanRole.BRANCH_MANAGER.spring())

                // 자동 심사 (배치/운영) — ROLE_OPS
                .requestMatchers(HttpMethod.POST,
                        "/api/loan-applications/*/review/auto-decide"
                ).hasRole(LoanRole.OPS.spring())

                // 수동 심사 실행·확정·편향확인 — ROLE_DEPUTY_MANAGER 또는 ROLE_OPS
                .requestMatchers(HttpMethod.POST,
                        "/api/loan-applications/*/review",
                        "/api/loan-applications/*/review/confirm",
                        "/api/loan-applications/*/review/acknowledge-bias"
                ).hasAnyRole(LoanRole.DEPUTY_MANAGER.spring(), LoanRole.OPS.spring())

                // 신용평가·DSR 실행 — 직원 전용 (DEPUTY·OPS)
                // (가심사 POST /prescreening 은 고객 한도조회와 같은 엔드포인트라 규칙 미적용)
                .requestMatchers(HttpMethod.POST,
                        "/api/loan-applications/*/credit-evaluation",
                        "/api/loan-applications/*/dsr-calculation"
                ).hasAnyRole(LoanRole.DEPUTY_MANAGER.spring(), LoanRole.OPS.spring())

                // 결정 정정(PATCH) — 최종 결재권자만 (BRANCH_MANAGER)
                // 경로가 /review 로 같아도 위 POST 규칙은 POST 한정이라 PATCH 는 별도 규칙 필요
                .requestMatchers(HttpMethod.PATCH,
                        "/api/loan-applications/*/review"
                ).hasRole(LoanRole.BRANCH_MANAGER.spring())

                // 승인자 최종 결재 — ROLE_BRANCH_MANAGER
                .requestMatchers(HttpMethod.POST,
                        "/api/loan-applications/*/review/approver-approve"
                ).hasRole(LoanRole.BRANCH_MANAGER.spring())

                // break-glass 긴급 접근 — 직원 역할만 (CUSTOMER 제외)
                .requestMatchers(HttpMethod.POST, "/api/break-glass")
                .hasAnyRole(
                        LoanRole.TELLER.spring(), LoanRole.DEPUTY_MANAGER.spring(),
                        LoanRole.BRANCH_MANAGER.spring(), LoanRole.HQ_REVIEWER.spring(),
                        LoanRole.COMPLIANCE.spring(), LoanRole.OPS.spring(),
                        LoanRole.ADMIN.spring()
                )

                // 알림 outbox 조회·재전송 — 운영자 전용.
                //
                // 이 엔드포인트는 채널별 발송 큐 전체를 돌려준다. customerId 필터가 없어
                // 고객 단위로 좁혀지지 않고, payload 에는 PII 가 들어갈 수 있다.
                // 규칙이 없어 anyRequest().authenticated() 로 떨어지고 있었다 —
                // 인증만 되면 고객도 남의 발송 이력을 볼 수 있었다.
                //
                // 고객용 알림함이 필요하다면 고객 소유가 드러나는 별도 도메인으로 만들어야 한다.
                // 발송 큐에 읽음 플래그를 얹는 것으로는 소유 문제가 해결되지 않는다.
                .requestMatchers("/api/notifications/**").hasAnyRole(
                        LoanRole.OPS.spring(), LoanRole.ADMIN.spring()
                )

                // 감사로그 조회 — COMPLIANCE 전용
                .requestMatchers("/api/audit/**").hasRole(LoanRole.COMPLIANCE.spring())

                // advisory 자문규칙·감사·통계·리포트 — HQ 계열 역할
                .requestMatchers("/api/advisory/**").hasAnyRole(
                        LoanRole.HQ_REVIEWER.spring(), LoanRole.COMPLIANCE.spring(),
                        LoanRole.OPS.spring(), LoanRole.ADMIN.spring()
                )

                .anyRequest().authenticated()
            );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:3000", "http://localhost:3001"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
