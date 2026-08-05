package com.bank.deposit.service;

import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.domain.enums.DirectionType;
import com.bank.deposit.domain.enums.TransactionChannel;
import com.bank.deposit.dto.interservice.*;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DepositV1Service {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long DEFAULT_PER_TX_LIMIT = 100_000_000L;
    private static final long DEFAULT_DAILY_LIMIT   = 500_000_000L;
    private static final long DEFAULT_MONTHLY_LIMIT = 3_000_000_000L;

    private final AccountService accountService;
    private final TransactionService transactionService;
    private final TransactionRepository transactionRepository;
    private final Clock clock;

    public AccountInfoResponse getAccountInfo(String accountNo) {
        Account a = accountService.findByAccountNumber(accountNo);
        return AccountInfoResponse.from(a);
    }

    public HolderInfoResponse getHolderInfo(String accountNo) {
        Account a = accountService.findByAccountNumber(accountNo);
        return HolderInfoResponse.from(a);
    }

    public BalanceResponse getBalance(String accountNo) {
        Account a = accountService.findByAccountNumber(accountNo);
        return BalanceResponse.from(a);
    }

    public LimitResponse getLimit(String accountNo, String dateParam) {
        Account a = accountService.findByAccountNumber(accountNo);
        Long accountId = a.getAccountId();

        LocalDate date = dateParam != null
                ? LocalDate.parse(dateParam, DateTimeFormatter.ofPattern("yyyyMMdd"))
                : LocalDate.now(clock.withZone(KST));

        OffsetDateTime dayStart   = date.atStartOfDay(KST).toOffsetDateTime();
        OffsetDateTime dayEnd     = date.plusDays(1).atStartOfDay(KST).toOffsetDateTime();
        OffsetDateTime monthStart = date.withDayOfMonth(1).atStartOfDay(KST).toOffsetDateTime();
        OffsetDateTime monthEnd   = date.withDayOfMonth(1).plusMonths(1).atStartOfDay(KST).toOffsetDateTime();

        long dailyUsed = transactionRepository
                .sumAmountByAccountIdAndDirectionTypeAndTransactionAtBetween(accountId, DirectionType.OUT, dayStart, dayEnd)
                .longValue();
        long monthlyUsed = transactionRepository
                .sumAmountByAccountIdAndDirectionTypeAndTransactionAtBetween(accountId, DirectionType.OUT, monthStart, monthEnd)
                .longValue();

        long perTx   = a.getDailyWithdrawLimit() != null ? a.getDailyWithdrawLimit().longValue() : DEFAULT_PER_TX_LIMIT;
        long daily   = a.getDailyWithdrawLimit() != null ? a.getDailyWithdrawLimit().longValue() : DEFAULT_DAILY_LIMIT;
        long monthly = DEFAULT_MONTHLY_LIMIT;

        return new LimitResponse(
                accountNo,
                date.format(DateTimeFormatter.ofPattern("yyyyMMdd")),
                daily,
                dailyUsed,
                Math.max(0, daily - dailyUsed),
                monthly,
                monthlyUsed,
                Math.max(0, monthly - monthlyUsed),
                perTx,
                "STANDARD"
        );
    }

    @Transactional
    public TransactionResponse withdraw(WithdrawRequest req, String idempotencyKey) {
        Account a = accountService.findByAccountNumber(req.accountNo());
        // 멱등 처리: 동일 키로 이미 처리된 출금이 있으면 그대로 반환
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = transactionRepository.findByIdempotencyKeyAndAccountId(idempotencyKey, a.getAccountId());
            if (existing.isPresent()) {
                return TransactionResponse.from(existing.get(), req.accountNo());
            }
        }
        Transaction tx = transactionService.withdraw(
                a.getAccountId(),
                BigDecimal.valueOf(req.amount()),
                TransactionChannel.INTERNET,
                req.memo()
        );
        return TransactionResponse.from(tx, req.accountNo());
    }

    @Transactional
    public TransactionResponse deposit(DepositRequest req, String idempotencyKey) {
        Account a = accountService.findByAccountNumber(req.accountNo());
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = transactionRepository.findByIdempotencyKeyAndAccountId(idempotencyKey, a.getAccountId());
            if (existing.isPresent()) {
                return TransactionResponse.from(existing.get(), req.accountNo());
            }
        }
        String depositorName = req.counterparty() != null ? req.counterparty().holderName() : null;
        Transaction tx = transactionService.deposit(
                a.getAccountId(),
                BigDecimal.valueOf(req.amount()),
                TransactionChannel.INTERNET,
                req.memo(),
                null,
                depositorName
        );
        return TransactionResponse.from(tx, req.accountNo());
    }

    @Transactional
    public CancelResponse cancelWithdraw(WithdrawCancelRequest req, String idempotencyKey) {
        Transaction original = transactionRepository.findByTransactionNumber(req.originalDepositTransactionNo())
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSACTION_NOT_FOUND));

        // 멱등 처리: 이미 취소 거래가 생성되어 있으면 그대로 반환
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = transactionRepository.findByIdempotencyKeyAndAccountId(idempotencyKey, original.getAccountId());
            if (existing.isPresent()) {
                return CancelResponse.from(existing.get(), req.originalDepositTransactionNo(), req.accountNo());
            }
        }

        Transaction reversal = transactionService.reversal(original.getTransactionId(), null);
        return CancelResponse.from(reversal, req.originalDepositTransactionNo(), req.accountNo());
    }
}
