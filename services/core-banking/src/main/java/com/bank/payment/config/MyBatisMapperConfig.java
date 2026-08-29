package com.bank.payment.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 결제계 MyBatis 매퍼 스캔.
 *
 * <p>진입점(CoreBankingApplication)이 아니라 별도 설정 클래스에 두는 이유가 있다.
 * 진입점에 붙이면 {@code @WebMvcTest} 같은 슬라이스 테스트도 이 애너테이션을 함께 읽어
 * 매퍼 빈을 등록하려 든다. 슬라이스에는 SqlSessionFactory 가 없으므로
 * "Property 'sqlSessionFactory' or 'sqlSessionTemplate' are required" 로 컨텍스트가 죽는다.
 *
 * <p>수신계 컨트롤러 슬라이스 테스트 13개가 결제계 인프라 때문에 깨지던 문제라,
 * 병합 후에도 각 도메인이 자기 슬라이스만 띄울 수 있게 분리해 둔다.
 * 슬라이스 필터는 일반 {@code @Configuration} 을 제외하므로 이 클래스는 딸려오지 않는다.
 *
 * <p>스캔 대상은 <b>패키지마다 적어야</b> 한다. 매퍼를 다른 패키지에 새로 만들고 여기에
 * 더하지 않으면 빈이 등록되지 않아, 그 매퍼를 주입받는 빈부터 컨텍스트 전체가 죽는다.
 */
@Configuration
@MapperScan({"com.bank.payment.domain.mapper", "com.bank.payment.security"})
public class MyBatisMapperConfig {
}
