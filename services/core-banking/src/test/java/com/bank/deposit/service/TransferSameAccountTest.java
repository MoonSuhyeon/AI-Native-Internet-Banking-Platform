package com.bank.deposit.service;

import com.bank.deposit.config.JpaAuditingConfig;
import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.domain.enums.ProductType;
import com.bank.deposit.domain.enums.TransactionChannel;
import com.bank.deposit.domain.enums.TransferType;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 자기 계좌로의 이체가 <b>원장에 닿기 전에</b> 막히는지 고정한다.
 *
 * <p>이 검사는 원래 상담 서비스가 이체를 부르기 <i>전에</i> 직접 DB 를 읽어서 했다.
 * 그러면 상담을 거치지 않는 경로 — 앱·창구·배치 — 로 같은 요청이 들어오면 그대로
 * 통과한다. 통과하면 같은 계좌에서 빼서 같은 계좌에 넣으므로 잔액은 그대로인데
 * 출금·입금 거래만 두 건 남는다. 원장이 사실과 어긋나는 것이다.
 *
 * <p>그래서 검사를 락을 쥔 쪽으로 옮겼고, 이 테스트는 그 자리를 지킨다.
 * 가드를 되돌리면 예외가 사라지고 거래 두 건이 생기면서 여기서 걸린다.
 */
@DataJpaTest
@Import({JpaAuditingConfig.class, TransactionService.class, IdempotentTransactionSaver.class})
@ActiveProfiles("test")
@DisplayName("이체 — 출금 계좌와 수취 계좌가 같으면 거절한다")
class TransferSameAccountTest {

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
    @DisplayName("같은 계좌 번호로 이체하면 거절되고 거래가 남지 않는다")
    void transfer_to_same_account_is_rejected() {
        Account account = persistedAccount("110-2222-0001", 11L);
        long balanceBefore = account.getBalance();
        long txCountBefore = transactionRepository.count();

        assertThatThrownBy(() -> transactionService.transfer(
                account.getAccountId(), account.getAccountId(), account.getAccountNumber(),
                10_000L, TransferType.INTERNAL, null, null, null,
                TransactionChannel.INTERNET, "자기 이체", "KEY-SAME-1"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TRANSFER_SAME_ACCOUNT);

        assertThat(transactionRepository.count())
                .as("막히지 않으면 잔액은 그대로인데 거래만 남아 원장이 사실과 어긋난다")
                .isEqualTo(txCountBefore);
        assertThat(account.getBalance()).isEqualTo(balanceBefore);
    }

    @Test
    @DisplayName("계좌 번호만 주고 계좌 번호가 출금 계좌와 같아도 거절한다")
    void transfer_resolved_by_account_number_is_also_rejected() {
        Account account = persistedAccount("110-2222-0002", 12L);

        // toAccountId 를 비우면 core-banking 이 계좌 번호로 찾아 채운다.
        // 그 뒤에도 같은 계좌면 막혀야 한다 — 부르는 쪽이 번호만 줬다고 통과하면 안 된다.
        assertThatThrownBy(() -> transactionService.transfer(
                account.getAccountId(), null, account.getAccountNumber(),
                10_000L, TransferType.INTERNAL, null, null, null,
                TransactionChannel.INTERNET, "자기 이체", "KEY-SAME-2"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TRANSFER_SAME_ACCOUNT);
    }

    @Test
    @DisplayName("다른 계좌로의 이체는 그대로 된다")
    void transfer_to_other_account_still_works() {
        Account from = persistedAccount("110-2222-0003", 13L);
        Account to = persistedAccount("110-2222-0004", 14L);

        var tx = transactionService.transfer(
                from.getAccountId(), to.getAccountId(), to.getAccountNumber(),
                10_000L, TransferType.INTERNAL, null, null, null,
                TransactionChannel.INTERNET, "이체", "KEY-OTHER-1");

        assertThat(tx.getTransactionId()).isNotNull();
        assertThat(from.getBalance()).isEqualTo(990_000L);
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
