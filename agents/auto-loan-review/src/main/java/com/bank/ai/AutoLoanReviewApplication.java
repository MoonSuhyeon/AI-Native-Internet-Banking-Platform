package com.bank.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * auto-loan-review 모듈은 RDB(JPA) 비사용. spring-data-jpa starter 는 build.gradle 에서 제외하고,
 * common 의 JPA 의존 패키지(audit/persistence/code)는 스캔에서 제외 — JpaRepository 클래스가
 * 클래스패스에 없어서 로드 시 NoClassDefFoundError 가 나기 때문. 필요한 건 common.web 뿐.
 *
 * <p>{@link ConfigurationPropertiesScan} 으로 com.bank.ai 하위의 record-based
 * {@code @ConfigurationProperties} (예: RuleEngineProperties) 를 일괄 활성화.
 */
@SpringBootApplication(scanBasePackages = {"com.bank.ai", "com.bank.common.web"})
@ConfigurationPropertiesScan("com.bank.ai")
@EnableScheduling
public class AutoLoanReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutoLoanReviewApplication.class, args);
    }
}
