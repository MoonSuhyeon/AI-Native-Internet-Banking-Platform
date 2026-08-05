package com.bank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * core-banking — 수신(deposit) + 결제(payment) 단일 진입점.
 *
 * <p>두 서비스를 합친 이유는 {@code docs/decisions/core-banking-merge.md} 참조.
 * 요약하면 자행이체가 하나의 사실인데 두 DB 에 쪼개져 보상 트랜잭션으로 이어져 있었다.
 *
 * <p>{@code KafkaAutoConfiguration} 을 제외하는 것은 결제계가 KFTC/BOK/Internal
 * 세 클러스터를 각각 직접 구성하기 때문이다. 자동설정이 단일 클러스터를 잡으면 충돌한다.
 */
// 스캔 범위를 명시한다.
// 진입점이 com.bank 로 올라오면서 이전에는 스캔되지 않던 com.bank.common 까지 잡혀
// 수신계의 JpaAuditingConfig 와 공통 모듈의 동명 빈이 충돌했다. 구 진입점은 각각
// com.bank.deposit / com.bank.payment 를 루트로 했으므로 그 범위를 그대로 유지한다.
@SpringBootApplication(
        scanBasePackages = {"com.bank.config", "com.bank.deposit", "com.bank.payment"},
        exclude = {KafkaAutoConfiguration.class})
@EnableFeignClients(basePackages = {"com.bank.deposit.client", "com.bank.payment.outbound.ledger"})
@EnableScheduling
public class CoreBankingApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreBankingApplication.class, args);
    }
}
