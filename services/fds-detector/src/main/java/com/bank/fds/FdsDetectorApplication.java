package com.bank.fds;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;

/**
 * 이상거래 탐지기.
 *
 * <p>결제계가 내는 {@code payment.completed} 를 별도 컨슈머 그룹으로 구독해
 * 룰과 이상탐지를 돌리고, 걸린 건만 조사 에이전트로 넘긴다.
 *
 * <p><b>자체 DB 가 없다.</b> 중복 방지와 시간창 집계는 Redis 로 한다 — 둘 다 TTL 이 있는
 * 휘발성 데이터다. 루트 build.gradle 이 모든 서비스에 JPA 를 넣어 주므로,
 * DataSource 자동설정을 꺼야 부팅한다.
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class
})
public class FdsDetectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(FdsDetectorApplication.class, args);
    }
}
