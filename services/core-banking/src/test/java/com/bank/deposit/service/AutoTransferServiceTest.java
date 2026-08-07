package com.bank.deposit.service;

import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.domain.entity.Contract;
import com.bank.deposit.domain.entity.PaymentSchedule;
import com.bank.deposit.domain.enums.*;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.repository.AccountRepository;
import com.bank.deposit.repository.ContractRepository;
import com.bank.deposit.repository.PaymentScheduleRepository;
import com.bank.deposit.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
@DisplayName("AutoTransferService")
class AutoTransferServiceTest {

    @Mock private PaymentScheduleRepository scheduleRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private ContractRepository contractRepository;

    private AutoTransferService autoTransferService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-15T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        autoTransferService = new AutoTransferService(
                scheduleRepository, accountRepository, transactionRepository, contractRepository, clock);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  자동이체 실행 (executeAutoTransfer)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("자동이체 실행")
    class ExecuteAutoTransfer {

        @Test
        @DisplayName("잔액 충분 시 자동이체 성공 — PAID로 변경, 잔액 이동, 연속실패 초기화")
        void success() {
            PaymentSchedule schedule = autoTransferSchedule(1L, 2L, 100_000L, 1);
            Contract contract = activeContract(1L, 0);
            Account source = account(1L, 500_000L);
            Account target = account(2L, 0L);

            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(source));
            given(accountRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));
            given(transactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            autoTransferService.executeAutoTransfer(schedule);

            assertThat(schedule.getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(schedule.getActualAmount()).isEqualTo(100000L);
            assertThat(source.getBalance()).isEqualTo(400000L);
            assertThat(target.getBalance()).isEqualTo(100000L);
            assertThat(contract.getConsecutiveMissCount()).isEqualTo(0);
            then(transactionRepository).should(times(2)).save(any());
        }

        @Test
        @DisplayName("잔액 부족 시 FAILED 처리 — INSUFFICIENT_BALANCE, 연속실패 1 증가")
        void insufficientBalance() {
            PaymentSchedule schedule = autoTransferSchedule(1L, 2L, 100_000L, 1);
            Contract contract = activeContract(1L, 0);
            Account source = account(1L, 50_000L);   // 10만 필요, 5만만 있음
            Account target = account(2L, 0L);

            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(source));
            given(accountRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));

            autoTransferService.executeAutoTransfer(schedule);

            assertThat(schedule.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(schedule.getFailureReasonCode()).isEqualTo(FailureReasonCode.INSUFFICIENT_BALANCE);
            assertThat(source.getBalance()).isEqualTo(50000L);  // 잔액 불변
            assertThat(target.getBalance()).isEqualTo(0L);      // 입금 없음
            assertThat(contract.getConsecutiveMissCount()).isEqualTo(1);
            then(transactionRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("출금 계좌 비활성 시 INVALID_ACCOUNT로 FAILED")
        void sourceAccountNotActive() {
            // sourceId=1L < targetId=2L 이므로 source 먼저 lock → CLOSED라서 예외 → target까지 미도달
            PaymentSchedule schedule = autoTransferSchedule(1L, 2L, 100_000L, 1);
            Contract contract = activeContract(1L, 0);
            Account closedSource = closedAccount(1L);

            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(closedSource));

            autoTransferService.executeAutoTransfer(schedule);

            assertThat(schedule.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(schedule.getFailureReasonCode()).isEqualTo(FailureReasonCode.INVALID_ACCOUNT);
        }

        @Test
        @DisplayName("sourceAccountId 미설정 시 INVALID_ACCOUNT로 즉시 FAILED")
        void noSourceAccountId() {
            PaymentSchedule schedule = PaymentSchedule.builder()
                    .contractId(1L).accountId(2L).paymentRound(1)
                    .scheduledDate(LocalDate.of(2026, 1, 15))
                    .scheduledAmount(100000L)
                    .isAutoTransfer(true)
                    .sourceAccountId(null)   // 미설정
                    .build();
            Contract contract = activeContract(1L, 0);

            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));

            autoTransferService.executeAutoTransfer(schedule);

            assertThat(schedule.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(schedule.getFailureReasonCode()).isEqualTo(FailureReasonCode.INVALID_ACCOUNT);
            then(accountRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("3회 연속 실패 시 자동이체 비활성화 + 계약 SUSPENDED")
        void thirdConsecutiveFailureSuspendsContract() {
            // 이미 2회 실패한 상태
            PaymentSchedule schedule = autoTransferSchedule(1L, 2L, 100_000L, 3);
            Contract contract = activeContract(1L, 2);  // consecutiveMissCount = 2
            Account source = account(1L, 0L);           // 잔액 없음 → 3번째 실패
            Account target = account(2L, 0L);

            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(source));
            given(accountRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));

            autoTransferService.executeAutoTransfer(schedule);

            assertThat(schedule.getStatus()).isEqualTo(PaymentStatus.SUSPENDED);
            assertThat(contract.getConsecutiveMissCount()).isEqualTo(3);
            assertThat(contract.getContractStatus()).isEqualTo(ContractStatus.SUSPENDED);
            assertThat(contract.getAutoTransferEnabled()).isFalse();
        }

        @Test
        @DisplayName("2회 연속 실패 후 성공 시 연속실패 0으로 초기화")
        void successAfterTwoFailuresResetsCount() {
            PaymentSchedule schedule = autoTransferSchedule(1L, 2L, 100_000L, 3);
            Contract contract = activeContract(1L, 2);  // 2회 실패 후
            Account source = account(1L, 500_000L);     // 이번엔 잔액 충분
            Account target = account(2L, 0L);

            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(source));
            given(accountRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));
            given(transactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            autoTransferService.executeAutoTransfer(schedule);

            assertThat(schedule.getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(contract.getConsecutiveMissCount()).isEqualTo(0);  // 리셋
            assertThat(contract.getContractStatus()).isEqualTo(ContractStatus.ACTIVE);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  수동 납입 지연 처리 (markManualOverdue)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("수동 납입 지연 처리")
    class MarkManualOverdue {

        @Test
        @DisplayName("납입 기한 초과 시 OVERDUE로 변경, 연속 미납 1 증가")
        void firstOverdue() {
            PaymentSchedule schedule = manualSchedule(1L, 2L, 100_000L, 1);
            Contract contract = activeContract(1L, 0);

            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));

            autoTransferService.markManualOverdue(schedule);

            assertThat(schedule.getStatus()).isEqualTo(PaymentStatus.OVERDUE);
            assertThat(contract.getConsecutiveMissCount()).isEqualTo(1);
            assertThat(contract.getContractStatus()).isEqualTo(ContractStatus.ACTIVE); // 1회는 유지
        }

        @Test
        @DisplayName("2회 연속 지연 — 계약 ACTIVE 유지")
        void secondOverdue() {
            PaymentSchedule schedule = manualSchedule(1L, 2L, 100_000L, 2);
            Contract contract = activeContract(1L, 1);  // 1회 이미 지연

            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));

            autoTransferService.markManualOverdue(schedule);

            assertThat(schedule.getStatus()).isEqualTo(PaymentStatus.OVERDUE);
            assertThat(contract.getConsecutiveMissCount()).isEqualTo(2);
            assertThat(contract.getContractStatus()).isEqualTo(ContractStatus.ACTIVE);
        }

        @Test
        @DisplayName("3회 연속 지연 시 계약 SUSPENDED")
        void thirdOverdueSuspendsContract() {
            PaymentSchedule schedule = manualSchedule(1L, 2L, 100_000L, 3);
            Contract contract = activeContract(1L, 2);  // 2회 이미 지연

            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));

            autoTransferService.markManualOverdue(schedule);

            assertThat(schedule.getStatus()).isEqualTo(PaymentStatus.SUSPENDED);
            assertThat(contract.getConsecutiveMissCount()).isEqualTo(3);
            assertThat(contract.getContractStatus()).isEqualTo(ContractStatus.SUSPENDED);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  수동 납입 처리 (executeManualPayment)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("수동 납입 처리")
    class ExecuteManualPayment {

        @Test
        @DisplayName("PENDING 스케줄 납입 성공 — PAID 변경, 잔액 이동, miss count 초기화")
        void payPendingSchedule() {
            PaymentSchedule schedule = manualSchedule(1L, 2L, 100_000L, 1);
            Contract contract = activeContract(1L, 0);
            Account source = account(3L, 500_000L);  // 고객 입출금 계좌
            Account target = account(2L, 0L);         // 적금 계좌

            given(scheduleRepository.findById(1L)).willReturn(Optional.of(schedule));
            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));
            // 2L < 3L 이므로 target 먼저 lock
            given(accountRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));
            given(accountRepository.findByIdForUpdate(3L)).willReturn(Optional.of(source));
            given(transactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            PaymentSchedule result = autoTransferService.executeManualPayment(1L, 3L);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(result.getActualAmount()).isEqualTo(100000L);
            assertThat(source.getBalance()).isEqualTo(400000L);
            assertThat(target.getBalance()).isEqualTo(100000L);
            assertThat(contract.getConsecutiveMissCount()).isEqualTo(0);
            then(transactionRepository).should(times(2)).save(any());
        }

        @Test
        @DisplayName("OVERDUE 스케줄 납입 — 성공, SUSPENDED 계약은 ACTIVE로 복구")
        void payOverdueScheduleRestoresSuspendedContract() {
            PaymentSchedule schedule = PaymentSchedule.builder()
                    .contractId(1L).accountId(2L).paymentRound(3)
                    .scheduledDate(LocalDate.of(2026, 1, 1))
                    .scheduledAmount(100000L)
                    .isAutoTransfer(false)
                    .status(PaymentStatus.OVERDUE)  // 연체 상태
                    .build();
            // 3회 실패로 이미 SUSPENDED된 계약
            Contract contract = Contract.builder()
                    .contractNumber("CTR-001").customerId("CUST-001").productId(10L)
                    .joinAmount(100000L)
                    .contractInterestRate(BigDecimal.valueOf(3.5))
                    .finalInterestRate(BigDecimal.valueOf(3.5))
                    .contractPeriodMonth(12).startedAt(LocalDate.of(2025, 1, 1))
                    .joinChannel(JoinChannel.WEB)
                    .contractStatus(ContractStatus.SUSPENDED)
                    .consecutiveMissCount(3)
                    .build();
            Account source = account(3L, 500_000L);
            Account target = account(2L, 0L);

            given(scheduleRepository.findById(1L)).willReturn(Optional.of(schedule));
            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));
            given(accountRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));
            given(accountRepository.findByIdForUpdate(3L)).willReturn(Optional.of(source));
            given(transactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            PaymentSchedule result = autoTransferService.executeManualPayment(1L, 3L);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(contract.getConsecutiveMissCount()).isEqualTo(0);     // 리셋
            assertThat(contract.getContractStatus()).isEqualTo(ContractStatus.ACTIVE); // 복구
        }

        @Test
        @DisplayName("이미 납입 완료된 스케줄에 재납입 시 예외")
        void payAlreadyPaidScheduleThrows() {
            PaymentSchedule paid = PaymentSchedule.builder()
                    .contractId(1L).accountId(2L).paymentRound(1)
                    .scheduledDate(LocalDate.of(2026, 1, 15))
                    .scheduledAmount(100000L)
                    .status(PaymentStatus.PAID)
                    .build();

            given(scheduleRepository.findById(1L)).willReturn(Optional.of(paid));

            assertThatThrownBy(() -> autoTransferService.executeManualPayment(1L, 3L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_STATUS);
        }

        @Test
        @DisplayName("SUSPENDED 스케줄에 납입 시도 시 예외")
        void paySuspendedScheduleThrows() {
            PaymentSchedule suspended = PaymentSchedule.builder()
                    .contractId(1L).accountId(2L).paymentRound(3)
                    .scheduledDate(LocalDate.of(2026, 1, 1))
                    .scheduledAmount(100000L)
                    .status(PaymentStatus.SUSPENDED)
                    .build();

            given(scheduleRepository.findById(1L)).willReturn(Optional.of(suspended));

            assertThatThrownBy(() -> autoTransferService.executeManualPayment(1L, 3L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_STATUS);
        }

        @Test
        @DisplayName("납입 중 잔액 부족 시 BusinessException 전파")
        void payWithInsufficientBalance() {
            PaymentSchedule schedule = manualSchedule(1L, 2L, 100_000L, 1);
            Contract contract = activeContract(1L, 0);
            Account source = account(3L, 1_000L);   // 1천만 있지만 10만 필요
            Account target = account(2L, 0L);

            given(scheduleRepository.findById(1L)).willReturn(Optional.of(schedule));
            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));
            given(accountRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));
            given(accountRepository.findByIdForUpdate(3L)).willReturn(Optional.of(source));

            assertThatThrownBy(() -> autoTransferService.executeManualPayment(1L, 3L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  연속 실패 카운트 경계 테스트
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("연속 실패 카운트 경계")
    class MissCountBoundary {

        @Test
        @DisplayName("자동이체 실패 1회 — ACTIVE 유지, FAILED 상태")
        void firstFailure_remainsActive() {
            PaymentSchedule schedule = autoTransferSchedule(1L, 2L, 50_000L, 1);
            Contract contract = activeContract(1L, 0);
            Account source = account(1L, 0L);
            Account target = account(2L, 0L);

            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(source));
            given(accountRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));

            autoTransferService.executeAutoTransfer(schedule);

            assertThat(contract.getConsecutiveMissCount()).isEqualTo(1);
            assertThat(contract.getContractStatus()).isEqualTo(ContractStatus.ACTIVE);
            assertThat(schedule.getStatus()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        @DisplayName("자동이체 실패 2회 — ACTIVE 유지, FAILED 상태")
        void secondFailure_remainsActive() {
            PaymentSchedule schedule = autoTransferSchedule(1L, 2L, 50_000L, 2);
            Contract contract = activeContract(1L, 1);
            Account source = account(1L, 0L);
            Account target = account(2L, 0L);

            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(source));
            given(accountRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));

            autoTransferService.executeAutoTransfer(schedule);

            assertThat(contract.getConsecutiveMissCount()).isEqualTo(2);
            assertThat(contract.getContractStatus()).isEqualTo(ContractStatus.ACTIVE);
            assertThat(schedule.getStatus()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        @DisplayName("자동이체 실패 3회 — 정확히 3회째에 SUSPENDED")
        void thirdFailure_suspended() {
            PaymentSchedule schedule = autoTransferSchedule(1L, 2L, 50_000L, 3);
            Contract contract = activeContract(1L, 2);
            Account source = account(1L, 0L);
            Account target = account(2L, 0L);

            given(contractRepository.findById(1L)).willReturn(Optional.of(contract));
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(source));
            given(accountRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));

            autoTransferService.executeAutoTransfer(schedule);

            assertThat(contract.getConsecutiveMissCount()).isEqualTo(3);
            assertThat(contract.getContractStatus()).isEqualTo(ContractStatus.SUSPENDED);
            assertThat(contract.getAutoTransferEnabled()).isFalse();
            assertThat(schedule.getStatus()).isEqualTo(PaymentStatus.SUSPENDED);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  픽스처
    // ══════════════════════════════════════════════════════════════════════════

    private PaymentSchedule autoTransferSchedule(Long contractId, Long accountId,
                                                  long amount, int round) {
        return PaymentSchedule.builder()
                .contractId(contractId)
                .accountId(accountId)
                .paymentRound(round)
                .scheduledDate(LocalDate.of(2026, 1, 15))
                .scheduledAmount(amount)
                .isAutoTransfer(true)
                .sourceAccountId(1L)  // source = accountId 1L
                .build();
    }

    private PaymentSchedule manualSchedule(Long contractId, Long accountId,
                                            long amount, int round) {
        return PaymentSchedule.builder()
                .contractId(contractId)
                .accountId(accountId)
                .paymentRound(round)
                .scheduledDate(LocalDate.of(2026, 1, 10))
                .scheduledAmount(amount)
                .isAutoTransfer(false)
                .build();
    }

    private Contract activeContract(Long productId, int missCount) {
        return Contract.builder()
                .contractNumber("CTR-20260101-TEST001")
                .customerId("CUST-001")
                .productId(productId)
                .joinAmount(1200000L)
                .contractInterestRate(BigDecimal.valueOf(3.5))
                .finalInterestRate(BigDecimal.valueOf(3.5))
                .contractPeriodMonth(12)
                .startedAt(LocalDate.of(2026, 1, 1))
                .joinChannel(JoinChannel.WEB)
                .autoTransferEnabled(true)
                .consecutiveMissCount(missCount)
                .build();
    }

    private Account account(Long accountId, long balance) {
        return Account.builder()
                .accountNumber("ACC-" + accountId)
                .customerId("CUST-001")
                .contractId(accountId)
                .accountType(ProductType.SAVINGS)
                .accountPassword("$2a$10$abcdefghijklmnopqrstuu9QwmFAnNd0x5QyZ9LhQOW7bpcE6Pj2a")
                .openedAt(LocalDate.of(2026, 1, 1))
                .balance(balance)
                .build();
    }

    private Account closedAccount(Long accountId) {
        return Account.builder()
                .accountNumber("ACC-CLOSED-" + accountId)
                .customerId("CUST-001")
                .contractId(accountId)
                .accountType(ProductType.SAVINGS)
                .accountPassword("$2a$10$abcdefghijklmnopqrstuu9QwmFAnNd0x5QyZ9LhQOW7bpcE6Pj2a")
                .openedAt(LocalDate.of(2026, 1, 1))
                .accountStatus(AccountStatus.CLOSED)
                .build();
    }
}
