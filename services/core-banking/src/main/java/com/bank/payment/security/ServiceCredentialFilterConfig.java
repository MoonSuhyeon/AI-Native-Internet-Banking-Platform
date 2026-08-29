package com.bank.payment.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 내부 API 주체 인증 필터 등록.
 *
 * <p>필터를 {@code @Component} 로 두지 않고 여기서 등록하는 이유가 있다. 슬라이스
 * 테스트({@code @WebMvcTest})는 필터 빈을 함께 등록하는데, 슬라이스에는
 * SqlSessionFactory 가 없어 매퍼 주입이 실패하고 컨텍스트가 통째로 죽는다.
 * 슬라이스 필터는 일반 {@code @Configuration} 을 제외하므로 이 클래스는 딸려오지 않는다.
 *
 * <p>{@code MyBatisMapperConfig} 가 같은 이유로 분리돼 있다.
 */
@Configuration
public class ServiceCredentialFilterConfig {

    @Bean
    public FilterRegistrationBean<ServiceCredentialFilter> serviceCredentialFilter(
            ServiceAuthorizationMapper mapper,
            ServiceAuthorizationAuditWriter auditWriter,
            @Value("${payment.internal-auth.enforce:false}") boolean enforce) {

        FilterRegistrationBean<ServiceCredentialFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ServiceCredentialFilter(mapper, auditWriter, enforce));
        // 필터 자체도 경로를 한 번 더 확인한다. 등록 패턴과 필터의 판단이 어긋나면
        // 어느 쪽이 진짜인지 알 수 없으므로, 좁은 쪽(필터)을 정본으로 둔다.
        registration.addUrlPatterns("/v1/internal/*", "/api/v1/internal/*");
        registration.setName("serviceCredentialFilter");
        return registration;
    }
}
