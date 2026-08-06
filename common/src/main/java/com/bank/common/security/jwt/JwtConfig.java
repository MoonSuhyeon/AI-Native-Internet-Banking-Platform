package com.bank.common.security.jwt;

import com.bank.common.security.DevSecretGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * jwt.secret 프로퍼티가 존재하는 서비스에서만 JwtProvider 빈을 등록한다.
 * api-gateway, customer-service 등 JWT 를 다루는 서비스의 application.yml 에
 * jwt.secret / jwt.access-token-validity / jwt.refresh-token-validity 를 설정한다.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
@ConditionalOnProperty(name = "jwt.secret")
public class JwtConfig {

    @Bean
    public JwtProvider jwtProvider(JwtProperties properties, Environment environment) {
        // 운영에서 개발용 기본 시크릿이면 여기서 기동을 끊는다.
        // 이 검사가 없으면 토큰 위조가 가능한 채로 조용히 떠 있게 된다.
        DevSecretGuard.verify("jwt.secret", properties.secret(), environment);
        return new JwtProvider(properties);
    }
}
