package com.bank.loan.config;

import com.bank.common.time.BusinessDate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 여신계 시각 공급원.
 *
 * <p><b>왜 빈으로 두는가.</b> 상환·중도상환·부분상환 서비스가 {@code OffsetDateTime.now()} 를
 * 메서드 안에서 직접 불렀다. 그러면 테스트에서 시각을 고정할 수 없어, 연체일수처럼 날짜
 * 경계에서만 틀어지는 계산을 검증할 방법이 없다. 실제로 KST 00:00~09:00 에만 나타나는
 * 결함을 고치고도 회귀 테스트를 붙이지 못했던 것이 이 배선이 없었기 때문이다.
 *
 * <p><b>존을 KST 로 고정하는 이유.</b> {@code Clock.systemDefaultZone()} 은 컨테이너에서
 * UTC 가 된다. 절대시각만 쓰는 경로는 그래도 무방하지만, 이 clock 으로부터 날짜를 파생하는
 * 곳이 생기면 다시 같은 함정에 빠진다. 수신계 clock 빈도 같은 이유로 KST 다.
 *
 * <p>테스트는 {@code @TestConfiguration} 이나 {@code @MockBean} 으로 이 빈을 대체한다
 * ({@code @ConditionalOnMissingBean}).
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.system(BusinessDate.ZONE);
    }
}
