package com.bank.customer.config;

import com.bank.common.security.BankRole;
import com.bank.common.security.jwt.JwtProvider;
import com.bank.customer.security.GatewayHeaderAuthFilter;
import com.bank.customer.security.JwtGatewayHeaderFallbackFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * JWT 검증은 api-gateway 에서 수행하고, 검증된 사용자 정보는 X-User-* 헤더로 전파된다.
 *
 * <p>customer-service 는 게이트웨이 헤더를 {@link GatewayHeaderAuthFilter} 로 SecurityContext 에
 * 올린 뒤, 직원 전용 {@code /api/v1/internal/**} 를 <em>직무별</em>로 보호한다. 프론트
 * RoleGate/admin-auth.ts 는 메뉴 표시 통제(presentation)일 뿐 API 직호출로 우회되므로,
 * 컴플라이언스·감사·회원 상태전이 등 고영향 엔드포인트는 여기서 {@link BankRole} 직무 그룹으로
 * 게이팅한다(어휘는 프론트 admin-auth.ts 와 동일 단일 소스). 그 외 경로는 게이트웨이 1차
 * 검증 + 내부망 신뢰로 permitAll 을 유지한다.
 *
 * <p>매처는 구체 경로 우선이라 순서가 중요하다(리터럴 access-logs/join-stats 를 와일드카드보다 먼저).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * CORS 허용 오리진.
     *
     * <p><b>왜 설정으로 뺐는가.</b> 목록이 코드에 박혀 있어 localhost 만 허용했다.
     * 게이트웨이가 CORS 를 처리하니 운영에서는 상관없다고 적혀 있었지만, 이 필터는
     * 그대로 돌아 <b>목록에 없는 오리진을 403 으로 막는다.</b> 배포 도메인에서 브라우저가
     * 보낸 POST 가 전부 "Invalid CORS request" 로 거절됐고, GET 은 Origin 을 안 보내
     * 통과해서 화면에서는 "로그인만 안 되는" 것으로 보였다.
     *
     * <p>기본값은 예전 목록 그대로라 로컬 개발은 영향이 없다.
     */
    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:3001,http://127.0.0.1:3001}")
    private List<String> allowedOrigins;


    /** 직원 역할(고객 제외) — /internal 관리 API 접근 허용 대상 (단일 소스: {@link BankRole#EMPLOYEE_ROLES}) */
    private static final String[] EMPLOYEE_ROLES = BankRole.employeeRolesForHasRole();

    @Bean
    public GatewayHeaderAuthFilter gatewayHeaderAuthFilter() {
        return new GatewayHeaderAuthFilter();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           @Nullable JwtProvider jwtProvider) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .addFilterBefore(gatewayHeaderAuthFilter(), UsernamePasswordAuthenticationFilter.class);

        // jwt.secret 이 설정된 경우(로컬, 게이트웨이 미경유)에만 JWT 폴백 필터 등록
        if (jwtProvider != null) {
            http.addFilterAfter(new JwtGatewayHeaderFallbackFilter(jwtProvider),
                    GatewayHeaderAuthFilter.class);
        }

        http
            .authorizeHttpRequests(auth -> auth
                    // 감사 접근로그 조회 — 감사/심사/지점장/창구 (AUDIT_VIEW). 리터럴이라 customers/** 보다 먼저.
                    .requestMatchers(HttpMethod.GET, "/api/v1/internal/customers/access-logs")
                            .hasAnyRole(BankRole.rolesForHasRole(BankRole.AUDIT_VIEW_ROLES))
                    // 가입 통계 대시보드 — 컴플라이언스/리스크 (JOIN_STATS). 리터럴이라 customers/** 보다 먼저.
                    .requestMatchers(HttpMethod.GET, "/api/v1/internal/customers/join-stats")
                            .hasAnyRole(BankRole.rolesForHasRole(BankRole.JOIN_STATS_ROLES))
                    // 고객 조회·상세·회원 라이프사이클(등급·신용·정지·해지·재활성화·접근기록) — 고객 데이터 열람 직군
                    .requestMatchers("/api/v1/internal/customers/**")
                            .hasAnyRole(BankRole.rolesForHasRole(BankRole.CUSTOMER_VIEW_ROLES))
                    // KYC·AML·제재·세무·관계/대리인 심사 — 컴플라이언스 데스크
                    .requestMatchers("/api/v1/internal/compliance/**", "/api/v1/internal/party/**")
                            .hasAnyRole(BankRole.rolesForHasRole(BankRole.COMPLIANCE_DESK_ROLES))
                    // FDS 룰·탐지·사고 — 리스크/컴플라이언스/운영
                    .requestMatchers("/api/v1/internal/fds/**")
                            .hasAnyRole(BankRole.rolesForHasRole(BankRole.FDS_ROLES))
                    // 그 외 직원 전용 관리 API — 최소한 직원 역할(코어 화이트리스트) 필요
                    .requestMatchers("/api/v1/internal/**").hasAnyRole(EMPLOYEE_ROLES)
                    // 그 외 — 게이트웨이 1차 검증 + 내부망 신뢰
                    .anyRequest().permitAll());

        return http.build();
    }

    /**
     * 로컬 개발에서 프론트(localhost:3000/3001)가 게이트웨이를 거치지 않고
     * customer-service 를 직접 호출(로그인·인증)할 때 필요한 CORS 허용.
     * 운영은 게이트웨이가 CORS 를 처리한다.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
