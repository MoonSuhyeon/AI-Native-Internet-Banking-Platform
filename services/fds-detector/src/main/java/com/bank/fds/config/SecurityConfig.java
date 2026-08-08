package com.bank.fds.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 내부 서비스 간 호출만 받는다.
 *
 * <p><b>왜 필요한가.</b> 루트 build.gradle 이 모든 서비스에 security 스타터를 넣기 때문에,
 * 설정을 두지 않으면 Boot 기본 폼로그인·basic 인증이 걸린다. 그러면 결제계가 부르는
 * 사전 점검 API 가 매번 401 이 되는데, 게이트가 판정 실패를 fail-soft 로 처리하므로
 * <b>소액은 그냥 통과하고 아무도 눈치채지 못한다</b> — 탐지가 꺼진 채로 도는 상태가 된다.
 *
 * <p>인증은 게이트웨이가 맡는 이 레포의 자세를 따른다(core-banking·customer-service 동일).
 * 이 서비스는 포트를 외부에 열지 않고 내부망에서만 호출된다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(AbstractHttpConfigurer::disable)
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll()   // 게이트웨이가 JWT 인증 → 백엔드는 통과
            );
        return http.build();
    }
}
