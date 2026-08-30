package com.bank.payment.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

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
    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:3001}")
    private List<String> allowedOrigins;


    @Value("${app.cors.enabled:false}")
    private boolean corsEnabled;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        if (corsEnabled) {
            http.cors(cors -> cors.configurationSource(corsConfigurationSource()));
        } else {
            http.cors(AbstractHttpConfigurer::disable);
        }
        http
            .csrf(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll()   // 게이트웨이가 JWT 인증 → 백엔드는 통과
            );
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
