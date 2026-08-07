package com.bank.deposit.config;

import com.bank.common.time.BusinessDate;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;

@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 테스트에서 Clock을 교체해 결정론적 시간 검증 가능.
     *
     * <p><b>왜 systemDefaultZone 이 아닌가.</b> 컨테이너 JVM 타임존은 UTC 라
     * {@code LocalDate.now(clock)} 이 UTC 날짜를 돌려줬다. 수신계는 이 방식으로 날짜를
     * 15곳에서 파생한다 — 자동이체 실행일, 만기 판정, 이자 계산 기준일 등. 한국 시간
     * 00:00~09:00 에는 그 값이 모두 하루 전이었다.
     *
     * <p>테스트는 이미 {@code Clock.fixed(..., ZoneId.of("Asia/Seoul"))} 로 KST 를 가정하고
     * 있었다. 즉 운영만 UTC 로 돌아 테스트가 이 결함을 볼 수 없는 상태였다. 운영을 테스트의
     * 가정에 맞춘다.
     *
     * <p>{@code OffsetDateTime.now(clock)} 20곳은 절대시각이라 존이 바뀌어도 같은 순간이다
     * (오프셋 표기만 +00:00 에서 +09:00 으로 바뀐다). TIMESTAMPTZ 컬럼에 저장되는 값도 같다.
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.system(BusinessDate.ZONE);
    }
}
