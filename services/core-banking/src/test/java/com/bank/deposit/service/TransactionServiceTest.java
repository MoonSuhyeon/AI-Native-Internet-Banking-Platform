package com.bank.deposit.service;

import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.domain.enums.*;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.repository.AccountRepository;
import com.bank.deposit.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;


import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionService")
class TransactionServiceTest {

    @InjectMocks
    private TransactionService transactionService;

    @Mock private TransactionRepository transactionRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private Clock clock;
    @Mock private IdempotentTransactionSaver idempotentTransactionSaver;

    @BeforeEach
    void setUpClock() {
        org.mockito.Mockito.lenient().when(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        org.mockito.Mockito.lenient().when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        org.mockito.Mockito.lenient().when(idempotentTransactionSaver.saveOrFetch(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("입금")
    class Deposit {

        @Test
        @DisplayName("입금하면 잔액이 증가하고 거래 내역이 저장된다")
        void deposit() {
            Account acc = activeAccount(100000L);
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(acc));
            given(transactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            Transaction result = transactionService.deposit(1L, 50000L,
                    TransactionChannel.INTERNET, "테스트 입금", null, null);

            assertThat(result.getTransactionType()).isEqualTo(TransactionType.DEPOSIT);
            assertThat(result.getDirectionType()).isEqualTo(DirectionType.IN);
            assertThat(result.getAmount()).isEqualTo(50000L);
            assertThat(result.getBalanceBefore()).isEqualTo(100000L);
            assertThat(result.getBalanceAfter()).isEqualTo(150000L);
            assertThat(result.getTransactionNumber()).startsWith("DEP-");
        }

        @Test
        @DisplayName("비활성 계좌에 입금하면 예외가 발생한다")
        void depositToInactiveAccount() {
            Account closed = closedAccount();
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(closed));

            assertThatThrownBy(() -> transactionService.deposit(1L, 50000L,
                    null, null, null, null))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("출금")
    class Withdraw {

        @Test
        @DisplayName("잔액이 충분하면 출금이 정상 처리된다")
        void withdraw() {
            Account acc = activeAccount(500000L);
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(acc));
            given(transactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            Transaction result = transactionService.withdraw(1L, 200000L,
                    TransactionChannel.ATM, "ATM 출금");

            assertThat(result.getDirectionType()).isEqualTo(DirectionType.OUT);
            assertThat(result.getBalanceAfter()).isEqualTo(300000L);
        }

        @Test
        @DisplayName("잔액보다 많은 금액을 출금하면 예외가 발생한다")
        void withdrawInsufficientBalance() {
            Account acc = activeAccount(10000L);
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(acc));

            assertThatThrownBy(() -> transactionService.withdraw(1L, 50000L,
                    null, null))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("이체")
    class Transfer {

        @Test
        @DisplayName("내부 이체 시 출금 거래가 생성된다")
        void transfer() {
            Account source = activeAccount(1000000L);
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(source));


            Transaction result = transactionService.transfer(1L, null, "001-1234-5678",
                    300000L, TransferType.EXTERNAL,
                    "001", "국민은행", "홍길동", TransactionChannel.INTERNET, "이체", null);

            assertThat(result.getTransactionType()).isEqualTo(TransactionType.TRANSFER);
            assertThat(result.getDirectionType()).isEqualTo(DirectionType.OUT);
            assertThat(result.getTransactionNumber()).startsWith("TRF-");
            assertThat(result.getTransferType()).isEqualTo(TransferType.EXTERNAL);
            assertThat(result.getCounterpartyAccountNo()).isEqualTo("001-1234-5678");
            assertThat(result.getCounterpartyBankCode()).isEqualTo("001");
            assertThat(result.getCounterpartyBankName()).isEqualTo("국민은행");
            assertThat(result.getCounterpartyName()).isEqualTo("홍길동");
            assertThat(result.getChannelType()).isEqualTo(TransactionChannel.INTERNET);
            assertThat(result.getTransferRequestedAt()).isNotNull();
            assertThat(result.getTransferCompletedAt()).isNotNull();
            assertThat(source.getBalance()).isEqualTo(700000L);
        }

        @Test
        @DisplayName("내부 계좌 이체 시 상대 계좌 입금 거래도 생성한다")
        void transferToInternalAccountCreatesInboundTransaction() {
            Account source = activeAccount(1000000L);
            Account target = Account.builder()
                    .accountNumber("ACC-002")
                    .customerId("CUST-002")
                    .contractId(2L)
                    .accountType(ProductType.DEPOSIT)
                    .accountPassword("5678")
                    .openedAt(LocalDate.of(2026, 1, 1))
                    .balance(200000L)
                    .build();
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(source));
            given(accountRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));
            given(transactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));


            Transaction result = transactionService.transfer(1L, 2L, "ACC-002",
                    300000L, TransferType.INTERNAL,
                    "001", "우리은행", "김수신", TransactionChannel.MOBILE, "내부 이체", null);

            // OUT 거래는 saveOrFetch, IN 거래는 transactionRepository.save
            ArgumentCaptor<Transaction> outCaptor = ArgumentCaptor.forClass(Transaction.class);
            then(idempotentTransactionSaver).should(org.mockito.Mockito.times(1)).saveOrFetch(outCaptor.capture(), any(), any());
            ArgumentCaptor<Transaction> inCaptor = ArgumentCaptor.forClass(Transaction.class);
            then(transactionRepository).should(org.mockito.Mockito.times(1)).save(inCaptor.capture());
            Transaction outTx = outCaptor.getValue();
            Transaction inTx = inCaptor.getValue();

            assertThat(result.getDirectionType()).isEqualTo(DirectionType.OUT);
            assertThat(source.getBalance()).isEqualTo(700000L);
            assertThat(target.getBalance()).isEqualTo(500000L);
            assertThat(outTx.getDirectionType()).isEqualTo(DirectionType.OUT);
            assertThat(inTx.getDirectionType()).isEqualTo(DirectionType.IN);
            assertThat(inTx.getTransferType()).isEqualTo(TransferType.INTERNAL);
            assertThat(inTx.getChannelType()).isEqualTo(TransactionChannel.SYSTEM);
            assertThat(inTx.getTransactionSummary()).isEqualTo("이체 수신");
        }

        @Test
        @DisplayName("잔액이 부족하면 이체 시 예외가 발생한다")
        void transferInsufficientBalance() {
            Account source = activeAccount(100L);
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(source));

            assertThatThrownBy(() -> transactionService.transfer(1L, 2L, null,
                    1000000L, null, null, null, null, null, null, null))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("INTERNAL 이체인데 toAccountId가 null이면 예외가 발생한다")
        void internalTransferWithNullToAccountId() {
            assertThatThrownBy(() -> transactionService.transfer(1L, null, null,
                    100000L, TransferType.INTERNAL,
                    null, null, null, null, null, null))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("존재하지 않는 출금 계좌로 이체하면 예외가 발생한다")
        void transferFromNonExistentAccount() {
            given(accountRepository.findByIdForUpdate(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> transactionService.transfer(99L, null, "001-0000-0001",
                    100000L, TransferType.EXTERNAL,
                    "001", "국민은행", "수취인", TransactionChannel.INTERNET, "이체", null))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("CLOSED 계좌에서 이체하면 예외가 발생한다")
        void transferFromClosedAccount() {
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(closedAccount()));

            assertThatThrownBy(() -> transactionService.transfer(1L, null, "001-0000-0001",
                    100000L, TransferType.EXTERNAL,
                    "001", "국민은행", "수취인", TransactionChannel.INTERNET, "이체", null))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("당행 이체 시 계좌번호가 ID와 불일치하면 예외가 발생한다")
        void internalTransferAccountNoMismatch() {
            Account source = activeAccount(1000000L);
            Account target = Account.builder()
                    .accountNumber("ACC-002")
                    .customerId("CUST-002")
                    .contractId(2L)
                    .accountType(ProductType.DEPOSIT)
                    .accountPassword("5678")
                    .openedAt(LocalDate.of(2026, 1, 1))
                    .balance(100000L)
                    .build();
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(source));
            given(accountRepository.findByIdForUpdate(2L)).willReturn(Optional.of(target));

            assertThatThrownBy(() -> transactionService.transfer(1L, 2L, "ACC-WRONG",
                    100000L, TransferType.INTERNAL,
                    null, null, null, TransactionChannel.INTERNET, "이체", null))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("타행 이체 후 출금 계좌 잔액이 정확히 차감된다")
        void externalTransferDeductsCorrectly() {
            Account source = activeAccount(500000L);
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(source));


            transactionService.transfer(1L, null, "302-1234-5678",
                    150000L, TransferType.EXTERNAL,
                    "SHB", "신한은행", "이수신", TransactionChannel.INTERNET, "타행 이체", null);

            assertThat(source.getBalance()).isEqualTo(350000L);
        }
    }

    @Nested
    @DisplayName("적금 납입")
    class SavingsPayment {

        @Test
        @DisplayName("적금 납입 시 잔액과 누적 납입액을 증가시킨다")
        void savingsPayment() {
            Account acc = activeAccount(100000L);
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(acc));
            given(transactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            Transaction result = transactionService.savingsPayment(1L, 10L,
                    50000L, 3, TransactionChannel.MOBILE);

            assertThat(result.getTransactionType()).isEqualTo(TransactionType.SAVINGS_PAYMENT);
            assertThat(result.getDirectionType()).isEqualTo(DirectionType.IN);
            assertThat(result.getContractId()).isEqualTo(10L);
            assertThat(result.getPaymentRound()).isEqualTo(3);
            assertThat(result.getBalanceBefore()).isEqualTo(100000L);
            assertThat(result.getBalanceAfter()).isEqualTo(150000L);
            assertThat(acc.getBalance()).isEqualTo(150000L);
            assertThat(acc.getTotalPaidAmount()).isEqualTo(50000L);
        }
    }

    @Nested
    @DisplayName("거래 취소")
    class Reversal {

        @Test
        @DisplayName("입금 거래를 취소하면 잔액이 감소하고 취소 거래가 생성된다")
        void reverseDepositTx() {
            Account acc = activeAccount(500000L);
            Transaction original = buildTx(1L, DirectionType.OUT, 100000L, TransactionStatus.SUCCESS);
            given(transactionRepository.findById(1L)).willReturn(Optional.of(original));
            given(accountRepository.findByIdForUpdate(original.getAccountId())).willReturn(Optional.of(acc));
            given(transactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            Transaction result = transactionService.reversal(1L, TransactionChannel.SYSTEM);

            assertThat(result.getTransactionType()).isEqualTo(TransactionType.REVERSAL);
            assertThat(result.getDirectionType()).isEqualTo(DirectionType.IN);
            assertThat(result.getTransactionNumber()).startsWith("REV-");
        }

        @Test
        @DisplayName("DEPOSIT 타입 거래는 취소할 수 없다")
        void reversalDepositNotAllowed() {
            Transaction depositTx = Transaction.builder()
                    .transactionNumber("DEP-20260101-ABCDEFGH")
                    .accountId(1L)
                    .transactionType(TransactionType.DEPOSIT)
                    .directionType(DirectionType.IN)
                    .amount(50000L)
                    .balanceBefore(100000L)
                    .balanceAfter(150000L)
                    .channelType(TransactionChannel.INTERNET)
                    .transactionAt(java.time.OffsetDateTime.now())
                    .build();
            given(transactionRepository.findById(1L)).willReturn(Optional.of(depositTx));

            assertThatThrownBy(() -> transactionService.reversal(1L, null))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("이미 취소된 거래를 재취소하면 예외가 발생한다")
        void reversalAlreadyCanceled() {
            Transaction canceled = buildTx(1L, DirectionType.IN, 100000L, TransactionStatus.CANCELED);
            given(transactionRepository.findById(1L)).willReturn(Optional.of(canceled));

            assertThatThrownBy(() -> transactionService.reversal(1L, null))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("일일 이체 한도")
    class DailyTransferLimit {

        private static final ZoneId KST = ZoneId.of("Asia/Seoul");
        private static final Instant FIXED = Instant.parse("2026-01-01T00:00:00Z"); // = KST 2026-01-01 09:00

        @BeforeEach
        void stubClockWithZone() {
            org.mockito.Mockito.lenient()
                    .when(clock.withZone(org.mockito.ArgumentMatchers.any(ZoneId.class)))
                    .thenReturn(Clock.fixed(FIXED, KST));
        }

        @Test
        @DisplayName("당일 누적 금액이 한도를 초과하면 예외가 발생한다")
        void dailyAmountLimitExceeded() {
            Account source = accountWithDailyAmountLimit(1000000L, 500000L);
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(source));
            given(transactionRepository.sumAmountByAccountIdAndDirectionTypeAndTransactionAtBetween(
                    any(), any(), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                    .willReturn(400000L); // 이미 40만 원 이체

            // 200,000 추가 → 총 600,000 > 한도 500,000
            assertThatThrownBy(() -> transactionService.transfer(1L, null, "ACC-EXT",
                    200000L, TransferType.EXTERNAL,
                    "001", "국민은행", "수취인", TransactionChannel.INTERNET, "이체", null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DAILY_TRANSFER_AMOUNT_EXCEEDED);
        }

        @Test
        @DisplayName("당일 이체 횟수가 한도에 달하면 예외가 발생한다")
        void dailyCountLimitExceeded() {
            Account source = accountWithDailyCountLimit(1000000L, 3);
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(source));
            given(transactionRepository.countByAccountIdAndDirectionTypeAndTransactionAtBetween(
                    any(), any(), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                    .willReturn(3L); // 이미 3건 = 한도 도달

            assertThatThrownBy(() -> transactionService.transfer(1L, null, "ACC-EXT",
                    100000L, TransferType.EXTERNAL,
                    "001", "국민은행", "수취인", TransactionChannel.INTERNET, "이체", null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DAILY_TRANSFER_COUNT_EXCEEDED);
        }

        @Test
        @DisplayName("KST 기준 시작 시각과 종료 시각이 정확히 전달된다")
        void kstBoundaryTimes() {
            Account source = accountWithDailyAmountLimit(1000000L, 500000L);
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(source));
            given(transactionRepository.sumAmountByAccountIdAndDirectionTypeAndTransactionAtBetween(
                    any(), any(), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                    .willReturn(0L);

            transactionService.transfer(1L, null, "ACC-EXT",
                    100000L, TransferType.EXTERNAL,
                    "001", "국민은행", "수취인", TransactionChannel.INTERNET, "이체", null);

            ArgumentCaptor<OffsetDateTime> startCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
            ArgumentCaptor<OffsetDateTime> endCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
            
            then(transactionRepository).should().sumAmountByAccountIdAndDirectionTypeAndTransactionAtBetween(
                    eq(1L), eq(DirectionType.OUT), startCaptor.capture(), endCaptor.capture());

            // KST 2026-01-01 00:00:00 = UTC 2025-12-31 15:00:00
            assertThat(startCaptor.getValue()).isEqualTo(OffsetDateTime.parse("2026-01-01T00:00+09:00"));
            assertThat(endCaptor.getValue()).isEqualTo(OffsetDateTime.parse("2026-01-02T00:00+09:00"));
        }

        @Test
        @DisplayName("한도가 설정되지 않은 계좌는 검증을 건너뛴다")
        void skipValidationWhenLimitsAreNull() {
            Account source = activeAccount(1000000L); // Limits are null
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(source));

            Transaction result = transactionService.transfer(1L, null, "ACC-EXT",
                    100000L, TransferType.EXTERNAL,
                    "001", "국민은행", "수취인", TransactionChannel.INTERNET, "이체", null);

            assertThat(result).isNotNull();
            then(transactionRepository).should(org.mockito.Mockito.never())
                    .sumAmountByAccountIdAndDirectionTypeAndTransactionAtBetween(any(), any(), any(), any());
        }

        @Test
        @DisplayName("금액·횟수 모두 한도 내이면 이체가 성공한다")
        void withinBothLimits() {
            Account source = accountWithBothLimits(
                    1000000L, 500000L, 3);
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(source));
            given(transactionRepository.sumAmountByAccountIdAndDirectionTypeAndTransactionAtBetween(
                    any(), any(), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                    .willReturn(100000L);
            given(transactionRepository.countByAccountIdAndDirectionTypeAndTransactionAtBetween(
                    any(), any(), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                    .willReturn(1L);

            Transaction result = transactionService.transfer(1L, null, "ACC-EXT",
                    100000L, TransferType.EXTERNAL,
                    "001", "국민은행", "수취인", TransactionChannel.INTERNET, "이체", null);

            assertThat(result).isNotNull();
        }

        private Account accountWithDailyAmountLimit(Long balance, Long amountLimit) {
            return Account.builder()
                    .accountId(1L)
                    .accountNumber("ACC-001")
                    .customerId("CUST-001")
                    .contractId(1L)
                    .accountType(ProductType.DEPOSIT)
                    .accountPassword("$2a$10$abcdefghijklmnopqrstuv") // Valid BCrypt prefix
                    .openedAt(LocalDate.of(2026, 1, 1))
                    .balance(balance)
                    .dailyWithdrawLimit(amountLimit)
                    .build();
        }

        private Account accountWithDailyCountLimit(Long balance, int countLimit) {
            return Account.builder()
                    .accountId(1L)
                    .accountNumber("ACC-001")
                    .customerId("CUST-001")
                    .contractId(1L)
                    .accountType(ProductType.DEPOSIT)
                    .accountPassword("$2a$10$abcdefghijklmnopqrstuv")
                    .openedAt(LocalDate.of(2026, 1, 1))
                    .balance(balance)
                    .dailyWithdrawCountLimit(countLimit)
                    .build();
        }

        private Account accountWithBothLimits(Long balance, Long amountLimit, int countLimit) {
            return Account.builder()
                    .accountId(1L)
                    .accountNumber("ACC-001")
                    .customerId("CUST-001")
                    .contractId(1L)
                    .accountType(ProductType.DEPOSIT)
                    .accountPassword("$2a$10$abcdefghijklmnopqrstuv")
                    .openedAt(LocalDate.of(2026, 1, 1))
                    .balance(balance)
                    .dailyWithdrawLimit(amountLimit)
                    .dailyWithdrawCountLimit(countLimit)
                    .build();
        }
    }

    @Nested
    @DisplayName("경계값 및 동시성")
    class EdgeCases {

        @Test
        @DisplayName("잔액과 정확히 같은 금액을 이체하면 성공하고 잔액이 0이 된다")
        void transferExactBalance() {
            Account source = activeAccount(300000L);
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(source));


            transactionService.transfer(1L, null, "ACC-002",
                    300000L, TransferType.EXTERNAL,
                    "001", "국민은행", "수취인", TransactionChannel.INTERNET, "전액 이체", null);

            assertThat(source.getBalance()).isEqualTo(0L);
        }

        @Test
        @DisplayName("단일 스레드에서 순차 이체 두 번 후 잔액이 정확하다")
        void sequentialTransferBalance() {
            Account source = activeAccount(1000000L);
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(source));


            transactionService.transfer(1L, null, "ACC-A",
                    300000L, TransferType.EXTERNAL,
                    "001", "국민은행", "수취인A", TransactionChannel.INTERNET, "이체1", null);
            transactionService.transfer(1L, null, "ACC-B",
                    200000L, TransferType.EXTERNAL,
                    "002", "신한은행", "수취인B", TransactionChannel.INTERNET, "이체2", null);

            assertThat(source.getBalance()).isEqualTo(500000L);
        }

        @Test
        @DisplayName("출금 후 잔액이 음수가 되지 않는다")
        void balanceNeverNegative() {
            Account source = activeAccount(100L);
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(source));

            assertThatThrownBy(() ->
                transactionService.withdraw(1L, 101L, TransactionChannel.INTERNET, "초과 출금"))
                    .isInstanceOf(BusinessException.class);

            // 실패했으므로 잔액은 그대로
            assertThat(source.getBalance()).isEqualTo(100L);
        }

        @Test
        @DisplayName("순차화된 여러 출금 서비스 호출 후 잔액과 거래 건수가 정확하다")
        void multiThreadSequentialBalance() throws Exception {
            Account source = activeAccount(1000000L);
            given(accountRepository.findByIdForUpdate(1L)).willReturn(Optional.of(source));
            given(transactionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            int threadCount = 5;
            long each = 50000L;
            Object sequentialCallLock = new Object();
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            try {
                for (int i = 0; i < threadCount; i++) {
                    futures.add(executor.submit(() -> {
                        latch.await();
                        synchronized (sequentialCallLock) {
                            transactionService.withdraw(1L, each, TransactionChannel.INTERNET, "스레드 출금");
                        }
                        return null;
                    }));
                }

                latch.countDown();
                for (Future<?> future : futures) {
                    future.get();
                }
            } finally {
                executor.shutdownNow();
            }

            assertThat(source.getBalance()).isEqualTo(750000L);
            then(transactionRepository).should(org.mockito.Mockito.times(threadCount)).save(any());
        }
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private Account activeAccount(Long balance) {
        return Account.builder()
                .accountNumber("ACC-001")
                .customerId("CUST-001")
                .contractId(1L)
                .accountType(ProductType.DEPOSIT)
                .accountPassword("1234")
                .openedAt(LocalDate.of(2026, 1, 1))
                .balance(balance)
                .build();
    }

    private Account closedAccount() {
        return Account.builder()
                .accountNumber("ACC-CLOSED")
                .customerId("CUST-001")
                .contractId(2L)
                .accountType(ProductType.DEPOSIT)
                .accountPassword("1234")
                .openedAt(LocalDate.of(2026, 1, 1))
                .accountStatus(AccountStatus.CLOSED)
                .build();
    }

    private Transaction buildTx(Long accountId, DirectionType direction,
                                 Long amount, TransactionStatus status) {
        Transaction tx = Transaction.builder()
                .transactionNumber("DEP-20260101-ABCDEFGH")
                .accountId(accountId)
                .transactionType(direction == DirectionType.IN ? TransactionType.DEPOSIT : TransactionType.WITHDRAW)
                .directionType(direction)
                .amount(amount)
                .balanceBefore(400000L)
                .balanceAfter(500000L)
                .channelType(TransactionChannel.INTERNET)
                .transactionAt(java.time.OffsetDateTime.now())
                .build();
        if (status == TransactionStatus.CANCELED) {
            tx.cancel();
        }
        return tx;
    }
}
