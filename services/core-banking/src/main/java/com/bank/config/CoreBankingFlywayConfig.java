package com.bank.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import javax.sql.DataSource;

/**
 * 스키마별 Flyway 인스턴스.
 *
 * <p>Spring Boot 자동설정 Flyway 는 히스토리 테이블이 하나뿐이라 두 마이그레이션 세트를
 * 다룰 수 없다. deposit(V1~V18)과 payment(V1~V22)가 <b>모두 V1 부터 시작</b>하기 때문에
 * 한 히스토리에 합치면 버전이 충돌한다. 그래서 자동설정을 끄고
 * ({@code spring.flyway.enabled=false}) 스키마마다 독립 인스턴스를 만든다.
 *
 * <p>번호를 다시 매기는 방법도 있지만 택하지 않았다. 두 도메인의 마이그레이션 이력은
 * 각자의 것이고, 합치는 순간 "이 변경이 어느 도메인 것인지"가 사라진다.
 * 히스토리 테이블을 스키마별로 두면 이력이 도메인에 붙어 남는다.
 *
 * <p>순서: deposit 을 먼저 올리고 payment 를 올린다. 지금은 두 스키마 사이에 FK 가 없어
 * 순서가 중요하지 않지만, 자행이체를 한 트랜잭션으로 묶으면서 참조가 생길 경우
 * 계좌(deposit)가 먼저 있어야 한다.
 */
// 전용 스위치를 둔다.
//
// spring.flyway.enabled 를 재사용하지 않는 이유: 그 값은 Boot 자동설정 Flyway 를 끄는 데
// 이미 쓰고 있다(application.yml 에서 false). 같은 키로 커스텀 인스턴스까지 제어하면
// "끄면 자동설정이 꺼지는지 우리 인스턴스가 꺼지는지" 알 수 없다.
// H2 로 도는 수신계 단위 테스트처럼 마이그레이션 자체가 필요 없는 곳에서 false 로 둔다.
@Configuration
@ConditionalOnProperty(name = "core-banking.flyway.enabled", matchIfMissing = true)
public class CoreBankingFlywayConfig {

    public static final String DEPOSIT_SCHEMA = "deposit";
    public static final String PAYMENT_SCHEMA = "payment";

    @Bean(initMethod = "migrate")
    public Flyway depositFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(DEPOSIT_SCHEMA)
                .defaultSchema(DEPOSIT_SCHEMA)
                .createSchemas(true)
                .locations("classpath:db-deposit/migration")
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .load();
    }

    @Bean(initMethod = "migrate")
    @DependsOn("depositFlyway")
    public Flyway paymentFlyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(PAYMENT_SCHEMA)
                .defaultSchema(PAYMENT_SCHEMA)
                .createSchemas(true)
                .locations("classpath:db-payment/migration")
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .load();
    }

    /**
     * JPA 가 스키마보다 먼저 뜨지 않게 막는다.
     *
     * <p>자동설정 Flyway 를 쓸 때는 Boot 가 이 의존을 걸어주지만, 직접 만든 인스턴스에는
     * 걸리지 않는다. 이게 없으면 EntityManagerFactory 가 아직 만들어지지 않은 스키마를
     * 검증하러 가서 기동이 실패할 수 있다.
     */
    @Bean
    public static EntityManagerFactoryDependsOnPostProcessor coreBankingFlywayDependsOnPostProcessor() {
        return new EntityManagerFactoryDependsOnPostProcessor("depositFlyway", "paymentFlyway") {
        };
    }
}
