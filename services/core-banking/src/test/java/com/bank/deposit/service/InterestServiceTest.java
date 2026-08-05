package com.bank.deposit.service;

import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.domain.entity.InterestHistory;
import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.domain.enums.InterestReason;
import com.bank.deposit.domain.enums.ProductType;
import com.bank.deposit.domain.enums.TaxBenefitType;
import com.bank.deposit.domain.enums.TransactionType;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.repository.AccountRepository;
import com.bank.deposit.repository.ContractRepository;
import com.bank.deposit.repository.InterestHistoryRepository;
import com.bank.deposit.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("InterestService")
class InterestServiceTest {

    private InterestService service;

    @Mock private InterestHistoryRepository interestHistoryRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private ContractRepository contractRepository;
    @Mock private TransactionRepository transactionRepository;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        service = new InterestService(interestHistoryRepository, accountRepository,
                contractRepository, transactionRepository, clock);
    }

    @Test
    @DisplayName("계약 ID로 이자 이력을 최신순 조회한다")
    void findByContract() {
        given(interestHistoryRepository.findByContractIdOrderByInterestPaidAtDesc(1L))
                .willReturn(List.of(history()));

        List<InterestHistory> result = service.findByContract(1L);

        assertThat(result).hasSize(1);
        then(interestHistoryRepository).should().findByContractIdOrderByInterestPaidAtDesc(1L);
    }

    @Test
    @DisplayName("존재하는 이자 이력을 조회한다")
    void findById() {
        given(interestHistoryRepository.findById(1L)).willReturn(Optional.of(history()));

        InterestHistory result = service.findById(1L);

        assertThat(result.getContractId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("존재하지 않는 이자 이력은 예외가 발생한다")
    void findByIdNotFound() {
        given(interestHistoryRepository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("계약별 세후 이자 합계를 조회한다")
    void sumByContract() {
        given(interestHistoryRepository.sumInterestAfterTaxByContractId(1L))
                .willReturn(BigDecimal.valueOf(87_500));

        BigDecimal result = service.sumByContract(1L);

        assertThat(result).isEqualByComparingTo("87500");
    }

    @Test
    @DisplayName("이자를 지급하면 이자 이력과 이자 거래가 저장되고 계좌 잔액이 증가한다")
    void payInterest() {
        Account account = account(BigDecimal.valueOf(1_000_000));
        given(accountRepository.findById(10L)).willReturn(Optional.of(account));
        given(interestHistoryRepository.save(any(InterestHistory.class))).willAnswer(inv -> inv.getArgument(0));
        given(transactionRepository.save(any(Transaction.class))).willAnswer(inv -> inv.getArgument(0));

        InterestHistory result = service.payInterest(
                1L, 10L,
                BigDecimal.valueOf(100_000),
                BigDecimal.valueOf(15_000),
                BigDecimal.valueOf(1_500),
                BigDecimal.valueOf(3.5),
                TaxBenefitType.GENERAL,
                BigDecimal.valueOf(0.154),
                InterestReason.REGULAR_INTEREST,
                "20260101",
                "20260331");

        assertThat(result.getInterestAfterTax()).isEqualByComparingTo("83500");
        assertThat(result.getInterestAmount()).isEqualByComparingTo("83500");
        assertThat(account.getBalance()).isEqualByComparingTo("1083500");
        assertThat(account.getTotalInterestAmount()).isEqualByComparingTo("83500");

        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        then(transactionRepository).should().save(txCaptor.capture());
        assertThat(txCaptor.getValue().getTransactionType()).isEqualTo(TransactionType.INTEREST);
        assertThat(txCaptor.getValue().getAmount()).isEqualByComparingTo("83500");
    }

    @Test
    @DisplayName("계좌가 없으면 이자 지급 시 예외가 발생한다")
    void payInterestAccountNotFound() {
        given(accountRepository.findById(10L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.payInterest(
                1L, 10L, BigDecimal.valueOf(100_000), null, null,
                BigDecimal.valueOf(3.5), null, null, null, "20260101", "20260331"))
                .isInstanceOf(BusinessException.class);
    }

    private Account account(BigDecimal balance) {
        return Account.builder()
                .accountNumber("ACC-001")
                .customerId("CUST-001")
                .contractId(1L)
                .accountType(ProductType.DEPOSIT)
                .accountPassword("1234")
                .openedAt(java.time.LocalDate.of(2026, 1, 1))
                .balance(balance)
                .build();
    }

    private InterestHistory history() {
        return InterestHistory.builder()
                .contractId(1L)
                .accountId(10L)
                .appliedInterestRate(BigDecimal.valueOf(3.5))
                .taxBenefitType(TaxBenefitType.GENERAL)
                .appliedTaxRate(BigDecimal.valueOf(0.154))
                .interestBeforeTax(BigDecimal.valueOf(100_000))
                .interestTaxAmount(BigDecimal.valueOf(15_000))
                .localIncomeTaxAmount(BigDecimal.valueOf(1_500))
                .interestAfterTax(BigDecimal.valueOf(83_500))
                .interestAmount(BigDecimal.valueOf(83_500))
                .interestReason(InterestReason.REGULAR_INTEREST)
                .interestPaidAt(OffsetDateTime.parse("2026-01-01T09:00:00+09:00"))
                .build();
    }
}
