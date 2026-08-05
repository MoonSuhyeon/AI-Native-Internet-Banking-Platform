package com.bank.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 결제계 통합테스트 베이스.
 *
 * 싱글턴 컨테이너 패턴 — PostgreSQL 1개 + Kafka 3개(kftc/bok/internal).
 * JVM 1회 기동, 여러 테스트 클래스가 동일 컨테이너를 재사용한다.
 * Flyway V1~V18 은 Spring 컨텍스트 초기화 시 자동 실행 (flyway.enabled=true default).
 *
 * JPA 미사용(MyBatis), Redis 미사용 — loan과 달리 해당 컨테이너 불필요.
 * SecurityConfig permitAll → JWT 헤더 불필요.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("mock")
@Import(AbstractPaymentIntegrationTest.KafkaTestOverride.class)
public abstract class AbstractPaymentIntegrationTest {

    @TestConfiguration
    static class KafkaTestOverride {
        @Bean
        static BeanPostProcessor kafkaMissingTopicsDisabler() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
                        factory.setMissingTopicsFatal(false);
                    }
                    return bean;
                }
            };
        }
    }

    static final PostgreSQLContainer<?> POSTGRES;
    static final KafkaContainer KAFKA_KFTC;
    static final KafkaContainer KAFKA_BOK;
    static final KafkaContainer KAFKA_INTERNAL;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:16");
        POSTGRES.start();

        KAFKA_KFTC     = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
        KAFKA_BOK      = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
        KAFKA_INTERNAL = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

        KAFKA_KFTC.start();
        KAFKA_BOK.start();
        KAFKA_INTERNAL.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);

        r.add("payment.kafka.kftc.bootstrap-servers",     KAFKA_KFTC::getBootstrapServers);
        r.add("payment.kafka.bok.bootstrap-servers",      KAFKA_BOK::getBootstrapServers);
        r.add("payment.kafka.internal.bootstrap-servers", KAFKA_INTERNAL::getBootstrapServers);

        // 토픽 미생성 시 fatal 방지 — 테스트 컨테이너는 토픽을 수동 생성하지 않음
        r.add("spring.kafka.listener.missing-topics-fatal", () -> "false");
    }

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper om;
    @Autowired protected JdbcTemplate jdbc;

    @BeforeEach
    void truncateAll() {
        jdbc.execute(
            "TRUNCATE TABLE payment_instruction, idempotency_key, ledger, external_call, " +
            "outbox_message, status_history, kftc_clearing_transaction, bok_settlement_transaction CASCADE"
        );
    }

    /**
     * POST /api/v1/payments 요청 빌더.
     * channel 필드는 DB CHECK 제약(WEB/MOBILE/BRANCH/ATM/OPEN_BANKING/INBOUND) — 검증된 값만 전달.
     */
    protected MockHttpServletRequestBuilder postPayment(
            String idempotencyKey,
            String userId,
            String authTokenId,
            String senderAccountId,
            String receiverBankCode,
            String receiverAccountNo,
            String receiverHolderName,
            long transferAmount,
            String channel) throws Exception {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("senderAccountId",              senderAccountId);
        body.put("receiverBankCode",             receiverBankCode);
        body.put("receiverAccountNo",            receiverAccountNo);
        body.put("receiverHolderName",           receiverHolderName);
        body.put("transferAmount",               BigDecimal.valueOf(transferAmount));
        body.put("receiverMemo",                 "이체");
        body.put("senderMemo",                   "송금");
        body.put("channel",                      channel);
        body.put("receiverPassbookSenderDisplay", "이몽룡");

        return post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Key", idempotencyKey)
                .header("X-User-Id",         userId)
                .header("X-Auth-Token-Id",   authTokenId)
                .content(om.writeValueAsString(body));
    }
}
