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
// deposit Mock 을 걷어냈다. 이제 같은 프로세스의 실제 수신계 원장에 붙는다.
// fault-injection 은 F5(분개 INSERT 실패) 주입용 이음새로만 남는다.
@ActiveProfiles("fault-injection")
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
        // 운영 설정과 동일하게 search_path 를 잡는다.
        // JPA 는 스스로 수식하지만 MyBatis 매퍼는 수식 없이 쓰므로 payment 가 경로에 있어야 한다.
        // Testcontainers 의 getJdbcUrl() 은 이미 쿼리 파라미터를 달고 나올 수 있으므로
        // 구분자를 상황에 맞게 고른다. ? 를 두 번 붙이면 파라미터가 통째로 무시된다.
        r.add("spring.datasource.url", () -> {
            String url = POSTGRES.getJdbcUrl();
            return url + (url.contains("?") ? "&" : "?") + "currentSchema=deposit,payment";
        });
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
            "TRUNCATE TABLE payment.payment_instruction, payment.idempotency_key, payment.ledger, " +
            "payment.external_call, payment.outbox_message, payment.status_history, " +
            "payment.kftc_clearing_transaction, payment.bok_settlement_transaction CASCADE"
        );
        // 수신계도 함께 비운다. 잔액이 테스트 간에 이월되면 "이체 후 잔액" 단언이 무의미해진다.
        jdbc.execute("TRUNCATE TABLE deposit.deposit_transactions, deposit.deposit_accounts CASCADE");
        seedAccounts();
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

        return post("/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Key", idempotencyKey)
                .header("X-User-Id",         userId)
                .header("X-Auth-Token-Id",   authTokenId)
                .content(om.writeValueAsString(body));
    }

    // ====================================================================
    // 수신계 시드
    // ====================================================================

    /** 이체 송신 계좌 — 잔액 20억(BOK 거액이체 10억까지 통과). */
    protected static final String SENDER_S1 = "12345678901234";
    /** 이체 수신 계좌 — 정상. */
    protected static final String RECEIVER_S1 = "12345678905678";
    /** 잔액 500만 — 600만 이체 시 INSUFFICIENT_BALANCE. */
    protected static final String SENDER_F1 = "77770000000001";
    /** CLOSED 계좌 — 입금 시도 시 거절. */
    protected static final String RECEIVER_CLOSED = "99990000000003";
    /**
     * 출금 후 입금 단계에서 실패시키는 수신 계좌.
     *
     * <p>계좌는 정상이고, DepositFailureSimulator 가 입금 시점에만 예외를 던진다.
     * CLOSED 계좌로 대체하면 출금 이전 검증에서 걸려 정작 검증하려던 경로를 지나가지 않는다.
     */
    protected static final String RECEIVER_F8 = "12345678909999";
    /** 분개 INSERT 를 실패시키는 수신 계좌 — LedgerFailureSimulator 가 이 번호를 보고 던진다. */
    protected static final String RECEIVER_F5 = "88880000";

    /**
     * 매 테스트마다 계좌를 새로 심는다.
     *
     * <p>Mock 을 걷어낸 뒤로 이체는 실제 잔액을 움직인다. 계좌를 초기화하지 않으면
     * 앞 테스트의 잔액이 이월돼 "이체 후 잔액" 단언이 무의미해진다.
     */
    protected void seedAccounts() {
        // 계좌는 계약을, 계약은 상품을 참조한다(FK). 운영에서는 시더가 상품을 넣지만
        // 테스트 프로필에서는 시더가 돌지 않으므로 여기서 최소한만 직접 심는다.
        jdbc.update("""
                INSERT INTO deposit.deposit_banking_products
                    (banking_product_id, deposit_product_type, deposit_product_name)
                VALUES (1, 'DEPOSIT', '결제 테스트용 예금')
                ON CONFLICT (banking_product_id) DO NOTHING
                """);

        seedAccount(SENDER_S1,       "CUST-S1", 2_000_000_000L, "ACTIVE");
        seedAccount(RECEIVER_S1,     "CUST-S2",     1_000_000L, "ACTIVE");
        seedAccount(SENDER_F1,       "CUST-F1",     5_000_000L, "ACTIVE");
        seedAccount(RECEIVER_CLOSED, "CUST-C1",             0L, "CLOSED");
        // F8 은 입금 단계에서 터뜨리는 시나리오라 계좌는 정상이어야 한다.
        // 출금까지 간 뒤 실패해야 롤백을 검증할 수 있다.
        seedAccount(RECEIVER_F8,     "CUST-F8",     1_000_000L, "ACTIVE");
        // F5 는 분개 단계에서 터뜨리는 시나리오라 계좌 자체는 정상이어야 한다.
        seedAccount(RECEIVER_F5,     "CUST-F5",     1_000_000L, "ACTIVE");
    }

    protected void seedAccount(String accountNo, String customerId, long balance, String status) {
        // 계좌번호에서 계약 id 를 유도한다 — 테스트끼리 겹치지 않으면서 재실행에도 같은 값이 나온다.
        // 계좌번호 길이가 제각각이라(8~14자리) 끝에서 최대 9자리만 취한다.
        int from = Math.max(0, accountNo.length() - 9);
        long contractId = Long.parseLong(accountNo.substring(from));

        jdbc.update("""
                INSERT INTO deposit.deposit_contracts
                    (contract_id, contract_number, customer_id, banking_product_id,
                     join_amount, contract_interest_rate, final_interest_rate,
                     contract_period_month, started_at, join_channel)
                VALUES (?, ?, ?, 1, 0, 0.00, 0.00, 12, '20250101', 'WEB')
                ON CONFLICT (contract_id) DO NOTHING
                """,
                contractId, "TEST-" + accountNo, customerId);

        jdbc.update("""
                INSERT INTO deposit.deposit_accounts
                    (account_number, customer_id, contract_id, account_type, bank_code,
                     account_alias, balance, currency, account_password,
                     daily_withdraw_limit, atm_withdraw_limit,
                     account_status, opened_at)
                VALUES (?, ?, ?, 'DEPOSIT', '004', ?, ?, 'KRW',
                        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
                        2000000000, 2000000000, ?, '20250101')
                """,
                accountNo, customerId, contractId,
                customerId + " 계좌", balance, status);
    }

    /** 계좌 잔액 조회 — 이체가 실제로 돈을 움직였는지 확인용. */
    protected long balanceOf(String accountNo) {
        return jdbc.queryForObject(
                "SELECT balance FROM deposit.deposit_accounts WHERE account_number = ?",
                java.math.BigDecimal.class, accountNo).longValue();
    }
}
