package com.bank.deposit.service;

import com.bank.deposit.config.JpaAuditingConfig;
import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.domain.enums.ProductType;
import com.bank.deposit.domain.enums.TransactionChannel;
import com.bank.deposit.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 멱등키가 <b>저장되는가</b>를 고정한다.
 *
 * <p>이 테스트가 없어서 오래 조용했던 결함이 있었다. v1 서비스간 API 는 멱등키를
 * {@code findByIdempotencyKeyAndAccountId} 로 <b>조회만</b> 했고, 그 아래
 * {@code withdraw}/{@code deposit}/{@code reversal} 은 키를 받지도 저장하지도
 * 않았다. 조회는 언제나 비어 있었고, 재시도는 매번 새 거래가 됐다.
 *
 * <p>그래서 여기서 묻는 것은 "중복이 막히는가" 가 아니라
 * <b>"키가 실제로 행에 남는가"</b> 다. 저장이 빠지면 나머지는 전부 무의미하다.
 */
@DataJpaTest
@Import({JpaAuditingConfig.class, TransactionService.class, IdempotentTransactionSaver.class})
@ActiveProfiles("test")
@DisplayName("멱등키 — 조회와 저장은 한 쌍이어야 한다")
class TransactionIdempotencyTest {

    @Autowired
    TestEntityManager em;

    @Autowired
    TransactionService transactionService;

    @Autowired
    TransactionRepository transactionRepository;

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Test
    @DisplayName("출금 시 넘긴 멱등키가 거래 행에 저장된다")
    void withdraw_persists_idempotency_key() {
        Account account = persistedAccount("110-1111-0001", 1L);

        Transaction tx = transactionService.withdraw(
                account.getAccountId(), 10_000L, TransactionChannel.INTERNET, "출금", "KEY-WDR-1");

        assertThat(tx.getIdempotencyKey()).isEqualTo("KEY-WDR-1");
        assertThat(transactionRepository
                .findByIdempotencyKeyAndAccountId("KEY-WDR-1", account.getAccountId()))
                .as("저장되지 않으면 조회는 영원히 비어 있고 멱등 검사가 죽는다")
                .isPresent();
    }

    @Test
    @DisplayName("같은 키로 다시 출금하면 새 거래가 생기지 않는다")
    void withdraw_with_same_key_does_not_create_second_transaction() {
        Account account = persistedAccount("110-1111-0002", 2L);

        Transaction first = transactionService.withdraw(
                account.getAccountId(), 10_000L, TransactionChannel.INTERNET, "출금", "KEY-WDR-2");
        long balanceAfterFirst = account.getBalance();

        Transaction replayed = transactionService.withdraw(
                account.getAccountId(), 10_000L, TransactionChannel.INTERNET, "출금", "KEY-WDR-2");

        assertThat(replayed.getTransactionId()).isEqualTo(first.getTransactionId());
        assertThat(account.getBalance())
                .as("재시도가 잔액을 두 번 깎으면 그것이 이중 출금이다")
                .isEqualTo(balanceAfterFirst);
    }

    @Test
    @DisplayName("입금도 같은 키면 한 번만 반영된다")
    void deposit_with_same_key_is_applied_once() {
        Account account = persistedAccount("110-1111-0003", 3L);

        Transaction first = transactionService.deposit(
                account.getAccountId(), 50_000L, TransactionChannel.INTERNET, "입금", null, "홍길동", "KEY-DEP-1");
        long balanceAfterFirst = account.getBalance();

        Transaction replayed = transactionService.deposit(
                account.getAccountId(), 50_000L, TransactionChannel.INTERNET, "입금", null, "홍길동", "KEY-DEP-1");

        assertThat(replayed.getTransactionId()).isEqualTo(first.getTransactionId());
        assertThat(account.getBalance()).isEqualTo(balanceAfterFirst);
    }

    @Test
    @DisplayName("키 없이 부르는 내부 경로는 그대로 동작한다")
    void without_key_still_works() {
        Account account = persistedAccount("110-1111-0004", 4L);

        Transaction tx = transactionService.withdraw(
                account.getAccountId(), 1_000L, TransactionChannel.INTERNET, "이자 출금");

        assertThat(tx.getTransactionId()).isNotNull();
        assertThat(tx.getIdempotencyKey())
                .as("창구·이자 같은 내부 발생 거래에는 외부 멱등키가 없다")
                .isNull();
    }

    /** Account 는 계좌비밀번호가 BCrypt 해시 형태인지 검사한다. */
    private static final String BCRYPT_STUB = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private Account persistedAccount(String accountNumber, long contractId) {
        Account account = Account.builder()
                .accountNumber(accountNumber)
                .customerId("1")
                .contractId(contractId)
                .accountType(ProductType.DEPOSIT)
                .accountPassword(BCRYPT_STUB)
                .openedAt(LocalDate.of(2026, 1, 1))
                .balance(1_000_000L)
                .build();
        em.persist(account);
        em.flush();
        return account;
    }
}
