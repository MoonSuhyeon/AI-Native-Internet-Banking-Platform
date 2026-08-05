package com.bank.deposit.repository;

import com.bank.deposit.config.JpaAuditingConfig;
import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.domain.enums.DirectionType;
import com.bank.deposit.domain.enums.TransactionChannel;
import com.bank.deposit.domain.enums.TransactionStatus;
import com.bank.deposit.domain.enums.TransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaAuditingConfig.class)
@ActiveProfiles("test")
@DisplayName("TransactionRepository.findByAccountIdInAndTransactionAtBetweenAndStatus")
class TransactionRepositoryTest {

    @Autowired
    TestEntityManager em;

    @Autowired
    TransactionRepository transactionRepository;

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2024-06-01T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    // 기간 기준점
    private static final OffsetDateTime BASE = OffsetDateTime.parse("2024-06-01T00:00:00+09:00");

    // ── 공통 헬퍼 ─────────────────────────────────────────────────────────────

    private Transaction tx(String txNo, Long accountId,
                           OffsetDateTime txAt, TransactionStatus status) {
        return Transaction.builder()
                .transactionNumber(txNo)
                .accountId(accountId)
                .transactionType(TransactionType.DEPOSIT)
                .directionType(DirectionType.IN)
                .amount(new BigDecimal("100000"))
                .balanceBefore(BigDecimal.ZERO)
                .balanceAfter(new BigDecimal("100000"))
                .channelType(TransactionChannel.MOBILE)
                .transactionAt(txAt)
                .status(status)
                .build();
    }

    private void save(Transaction t) {
        em.persist(t);
        em.flush();
    }

    // ── 테스트 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("여러 accountId를 넘기면 해당 계좌들의 거래를 함께 조회한다")
    void multipleAccountIds_areAllIncluded() {
        // given: accountId 1, 2, 3 각 1건 — 모두 기간 내 SUCCESS
        OffsetDateTime inRange = BASE.plusDays(1);
        save(tx("TX-MULTI-1", 1L, inRange, TransactionStatus.SUCCESS));
        save(tx("TX-MULTI-2", 2L, inRange, TransactionStatus.SUCCESS));
        save(tx("TX-MULTI-3", 3L, inRange, TransactionStatus.SUCCESS)); // 조회 대상 외

        // when: accountId 1, 2만 요청
        List<Transaction> result = transactionRepository
                .findByAccountIdInAndTransactionAtBetweenAndStatus(
                        List.of(1L, 2L), BASE, BASE.plusDays(30), TransactionStatus.SUCCESS);

        // then: 2건만 반환, accountId 1과 2
        assertThat(result).hasSize(2);
        assertThat(result).extracting(Transaction::getAccountId)
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("transactionAt 기간 필터가 적용된다 — 기간 내 거래만 반환한다")
    void periodFilter_returnsOnlyWithinRange() {
        // given: 기간 내 1건 / 기간 이전 1건 / 기간 이후 1건
        save(tx("TX-PF-IN",     1L, BASE.plusDays(5),  TransactionStatus.SUCCESS));
        save(tx("TX-PF-BEFORE", 1L, BASE.minusDays(1), TransactionStatus.SUCCESS));
        save(tx("TX-PF-AFTER",  1L, BASE.plusDays(40), TransactionStatus.SUCCESS));

        // when: BASE ~ BASE+30일
        List<Transaction> result = transactionRepository
                .findByAccountIdInAndTransactionAtBetweenAndStatus(
                        List.of(1L), BASE, BASE.plusDays(30), TransactionStatus.SUCCESS);

        // then: 기간 내 1건만 반환
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTransactionNumber()).isEqualTo("TX-PF-IN");
    }

    @Test
    @DisplayName("status=SUCCESS인 거래만 조회된다 — FAILED·PENDING은 제외된다")
    void statusFilter_returnsOnlySuccess() {
        // given: 같은 accountId·기간 내 SUCCESS, FAILED, PENDING 각 1건
        OffsetDateTime inRange = BASE.plusDays(2);
        save(tx("TX-ST-SUCCESS", 1L, inRange, TransactionStatus.SUCCESS));
        save(tx("TX-ST-FAILED",  1L, inRange, TransactionStatus.FAILED));
        save(tx("TX-ST-PENDING", 1L, inRange, TransactionStatus.PENDING));

        // when: SUCCESS만 요청
        List<Transaction> result = transactionRepository
                .findByAccountIdInAndTransactionAtBetweenAndStatus(
                        List.of(1L), BASE, BASE.plusDays(30), TransactionStatus.SUCCESS);

        // then: SUCCESS 1건만 반환
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(result.get(0).getTransactionNumber()).isEqualTo("TX-ST-SUCCESS");
    }

    @Test
    @DisplayName("기간 밖 거래는 결과에서 제외된다 — 경계 직전·직후 모두 제외")
    void outsidePeriod_isExcluded() {
        // given: 기간 시작 1초 전, 기간 종료 1일 후 각 1건
        save(tx("TX-OOB-BEFORE", 1L, BASE.minusSeconds(1), TransactionStatus.SUCCESS));
        save(tx("TX-OOB-AFTER",  1L, BASE.plusDays(31),    TransactionStatus.SUCCESS));

        // when: BASE ~ BASE+30일
        List<Transaction> result = transactionRepository
                .findByAccountIdInAndTransactionAtBetweenAndStatus(
                        List.of(1L), BASE, BASE.plusDays(30), TransactionStatus.SUCCESS);

        // then: 결과 없음
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("accountId 목록에 없는 계좌의 거래는 제외된다")
    void accountIdNotInList_isExcluded() {
        // given: accountId 10 거래 1건 (기간 내, SUCCESS)
        save(tx("TX-ACC-OTHER", 10L, BASE.plusDays(1), TransactionStatus.SUCCESS));

        // when: accountId 1, 2로 조회
        List<Transaction> result = transactionRepository
                .findByAccountIdInAndTransactionAtBetweenAndStatus(
                        List.of(1L, 2L), BASE, BASE.plusDays(30), TransactionStatus.SUCCESS);

        // then: 결과 없음
        assertThat(result).isEmpty();
    }
}
