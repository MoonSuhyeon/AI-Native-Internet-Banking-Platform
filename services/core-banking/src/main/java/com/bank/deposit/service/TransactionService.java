package com.bank.deposit.service;

import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.domain.enums.*;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.repository.AccountRepository;
import com.bank.deposit.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final Clock clock;
    private final IdempotentTransactionSaver idempotentTransactionSaver;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    public Page<Transaction> findByAccount(Long accountId, OffsetDateTime startDate, OffsetDateTime endDate, Pageable pageable) {
        if (startDate != null && endDate != null) {
            return transactionRepository.findByAccountIdAndTransactionAtBetween(accountId, startDate, endDate, pageable);
        }
        return transactionRepository.findByAccountId(accountId, pageable);
    }

    public Page<Transaction> findByCustomer(String customerId, Pageable pageable) {
        List<Long> accountIds = accountRepository.findByCustomerId(customerId)
                .stream().map(a -> a.getAccountId()).toList();
        if (accountIds.isEmpty()) return Page.empty(pageable);
        return transactionRepository.findByAccountIdIn(accountIds, pageable);
    }

    public Transaction findById(Long id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSACTION_NOT_FOUND));
    }

    @Transactional
    public Transaction deposit(Long accountId, Long amount, TransactionChannel channelType,
                               String transactionMemo, String depositorCustomerId, String depositorName) {
        return deposit(accountId, amount, channelType, transactionMemo, depositorCustomerId, depositorName, null);
    }

    /**
     * 입금. 멱등키가 있으면 같은 키의 기존 거래를 그대로 돌려준다.
     *
     * <p>키를 조회만 하고 저장하지 않으면 재시도가 매번 새 입금이 된다. 조회·저장은
     * 한 쌍이어야 한다.
     */
    @Transactional
    public Transaction deposit(Long accountId, Long amount, TransactionChannel channelType,
                               String transactionMemo, String depositorCustomerId, String depositorName,
                               String idempotencyKey) {
        Optional<Transaction> replayed = findByIdempotencyKey(idempotencyKey, accountId);
        if (replayed.isPresent()) {
            return replayed.get();
        }
        Account account = getActiveAccountForUpdate(accountId);
        Long before = account.getBalance();
        account.deposit(amount, clock);

        Transaction built = Transaction.builder()
                .transactionNumber(generateTxnNumber("DEP"))
                .accountId(accountId)
                .transactionType(TransactionType.DEPOSIT)
                .directionType(DirectionType.IN)
                .amount(amount)
                .balanceBefore(before)
                .balanceAfter(account.getBalance())
                .availableBalanceAfter(account.getBalance())
                .channelType(channelType != null ? channelType : TransactionChannel.INTERNET)
                .transactionAt(OffsetDateTime.now(clock))
                .postedAt(OffsetDateTime.now(clock))
                .transactionMemo(transactionMemo)
                .depositorCustomerId(depositorCustomerId)
                .depositorName(depositorName)
                .idempotencyKey(normalizeIdempotencyKey(idempotencyKey))
                .build();
        return persistIdempotent(built, idempotencyKey, accountId);
    }

    @Transactional
    public Transaction withdraw(Long accountId, Long amount, TransactionChannel channelType, String transactionMemo) {
        return withdraw(accountId, amount, channelType, transactionMemo, null);
    }

    /** 출금. 멱등키가 있으면 같은 키의 기존 거래를 그대로 돌려준다. */
    @Transactional
    public Transaction withdraw(Long accountId, Long amount, TransactionChannel channelType,
                                String transactionMemo, String idempotencyKey) {
        Optional<Transaction> replayed = findByIdempotencyKey(idempotencyKey, accountId);
        if (replayed.isPresent()) {
            return replayed.get();
        }
        Account account = getActiveAccountForUpdate(accountId);
        Long before = account.getBalance();
        account.withdraw(amount, clock);

        Transaction built = Transaction.builder()
                .transactionNumber(generateTxnNumber("WDR"))
                .accountId(accountId)
                .transactionType(TransactionType.WITHDRAW)
                .directionType(DirectionType.OUT)
                .amount(amount)
                .balanceBefore(before)
                .balanceAfter(account.getBalance())
                .availableBalanceAfter(account.getBalance())
                .channelType(channelType != null ? channelType : TransactionChannel.INTERNET)
                .transactionAt(OffsetDateTime.now(clock))
                .postedAt(OffsetDateTime.now(clock))
                .transactionMemo(transactionMemo)
                .idempotencyKey(normalizeIdempotencyKey(idempotencyKey))
                .build();
        return persistIdempotent(built, idempotencyKey, accountId);
    }

    /**
     * 내부/외부 이체.
     *
     * <p>수정 사항:
     * <ul>
     *   <li>수신 계좌가 없으면 {@link ErrorCode#ACCOUNT_NOT_FOUND} 예외 — 돈 증발 방지</li>
     *   <li>toAccountNo 가 toAccountId 의 accountNumber 와 일치하는지 검증</li>
     *   <li>수신 측 transferType 을 요청 값 그대로 반영 (EXTERNAL → EXTERNAL)</li>
     * </ul>
     */
    @Transactional
    public Transaction transfer(Long fromAccountId, Long toAccountId, String toAccountNo,
                                Long amount, TransferType transferType,
                                String counterpartyBankCode, String counterpartyBankName,
                                String counterpartyName, TransactionChannel channelType, String transactionMemo,
                                String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<Transaction> existing = transactionRepository
                    .findByIdempotencyKeyAndAccountId(idempotencyKey, fromAccountId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        TransferType resolvedType = transferType != null ? transferType : TransferType.INTERNAL;
        if (resolvedType == TransferType.INTERNAL && toAccountId == null) {
            toAccountId = accountRepository.findByAccountNumber(toAccountNo)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND))
                    .getAccountId();
        }

        Account source;
        Account target = null;
        if (resolvedType == TransferType.INTERNAL) {
            if (fromAccountId <= toAccountId) {
                source = getActiveAccountForUpdate(fromAccountId);
                target = getActiveAccountForUpdate(toAccountId);
            } else {
                target = getActiveAccountForUpdate(toAccountId);
                source = getActiveAccountForUpdate(fromAccountId);
            }
            validateCounterpartyAccountNo(toAccountId, toAccountNo, target);
        } else {
            source = getActiveAccountForUpdate(fromAccountId);
            if (toAccountId != null) {
                Account counterparty = getActiveAccountForUpdate(toAccountId);
                validateCounterpartyAccountNo(toAccountId, toAccountNo, counterparty);
            }
        }

        validateDailyTransferLimit(source, amount);

        Long before = source.getBalance();
        source.withdraw(amount, clock);
        OffsetDateTime now = OffsetDateTime.now(clock);

        Transaction built = Transaction.builder()
                .transactionNumber(generateTxnNumber("TRF"))
                .idempotencyKey(idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : null)
                .accountId(fromAccountId)
                .transactionType(TransactionType.TRANSFER)
                .directionType(DirectionType.OUT)
                .amount(amount)
                .balanceBefore(before)
                .balanceAfter(source.getBalance())
                .availableBalanceAfter(source.getBalance())
                .transferType(resolvedType)
                .counterpartyAccountId(toAccountId)
                .counterpartyAccountNo(toAccountNo)
                .counterpartyBankCode(counterpartyBankCode)
                .counterpartyBankName(counterpartyBankName)
                .counterpartyName(counterpartyName)
                .channelType(channelType != null ? channelType : TransactionChannel.INTERNET)
                .transactionAt(now)
                .postedAt(now)
                .transferRequestedAt(now)
                .transferCompletedAt(now)
                .transactionMemo(transactionMemo)
                .build();

        Transaction outTx = idempotentTransactionSaver.saveOrFetch(built, idempotencyKey, fromAccountId);

        if (resolvedType == TransferType.INTERNAL) {
            Long targetBefore = target.getBalance();
            target.deposit(amount, clock);
            transactionRepository.save(Transaction.builder()
                    .transactionNumber(generateTxnNumber("TRF"))
                    .accountId(toAccountId)
                    .transactionType(TransactionType.TRANSFER)
                    .directionType(DirectionType.IN)
                    .amount(amount)
                    .balanceBefore(targetBefore)
                    .balanceAfter(target.getBalance())
                    .availableBalanceAfter(target.getBalance())
                    .transferType(resolvedType)
                    .counterpartyAccountId(fromAccountId)
                    .channelType(TransactionChannel.SYSTEM)
                    .transactionAt(now)
                    .postedAt(now)
                    .transactionSummary("이체 수신")
                    .build());
        }
        return outTx;
    }

    @Transactional
    public Transaction savingsPayment(Long accountId, Long contractId, Long amount,
                                      Integer paymentRound, TransactionChannel channelType) {
        Account account = getActiveAccountForUpdate(accountId);
        Long before = account.getBalance();
        account.deposit(amount, clock);
        account.addPaidAmount(amount);

        return transactionRepository.save(Transaction.builder()
                .transactionNumber(generateTxnNumber("SAV"))
                .accountId(accountId)
                .contractId(contractId)
                .transactionType(TransactionType.SAVINGS_PAYMENT)
                .directionType(DirectionType.IN)
                .amount(amount)
                .balanceBefore(before)
                .balanceAfter(account.getBalance())
                .availableBalanceAfter(account.getBalance())
                .channelType(channelType != null ? channelType : TransactionChannel.SYSTEM)
                .paymentRound(paymentRound)
                .transactionAt(OffsetDateTime.now(clock))
                .postedAt(OffsetDateTime.now(clock))
                .build());
    }

    @Transactional
    public Transaction reversal(Long transactionId, TransactionChannel channelType) {
        return reversal(transactionId, channelType, null);
    }

    /** 거래 취소(보상). 멱등키가 있으면 같은 키의 기존 취소 거래를 그대로 돌려준다. */
    @Transactional
    public Transaction reversal(Long transactionId, TransactionChannel channelType, String idempotencyKey) {
        Transaction original = findById(transactionId);
        Optional<Transaction> replayed = findByIdempotencyKey(idempotencyKey, original.getAccountId());
        if (replayed.isPresent()) {
            return replayed.get();
        }
        if (original.getStatus() == TransactionStatus.CANCELED) {
            throw new BusinessException(ErrorCode.ALREADY_CANCELED);
        }
        if (original.getTransactionType() != TransactionType.WITHDRAW
                && original.getTransactionType() != TransactionType.TRANSFER) {
            throw new BusinessException(ErrorCode.INVALID_STATUS, "출금 또는 이체 거래만 취소할 수 있습니다.");
        }

        Account account = getActiveAccountForUpdate(original.getAccountId());
        Long before = account.getBalance();
        DirectionType reverseDirection = original.getDirectionType() == DirectionType.IN
                ? DirectionType.OUT : DirectionType.IN;

        if (reverseDirection == DirectionType.OUT) {
            account.withdraw(original.getAmount(), clock);
        } else {
            account.deposit(original.getAmount(), clock);
        }

        original.cancel(clock);

        Transaction built = Transaction.builder()
                .transactionNumber(generateTxnNumber("REV"))
                .accountId(original.getAccountId())
                .contractId(original.getContractId())
                .transactionType(TransactionType.REVERSAL)
                .directionType(reverseDirection)
                .amount(original.getAmount())
                .balanceBefore(before)
                .balanceAfter(account.getBalance())
                .availableBalanceAfter(account.getBalance())
                .channelType(channelType != null ? channelType : TransactionChannel.SYSTEM)
                .originalTransactionId(transactionId)
                .transactionAt(OffsetDateTime.now(clock))
                .postedAt(OffsetDateTime.now(clock))
                .transactionSummary("거래 취소")
                .idempotencyKey(normalizeIdempotencyKey(idempotencyKey))
                .build();
        return persistIdempotent(built, idempotencyKey, original.getAccountId());
    }

    private Optional<Transaction> findByIdempotencyKey(String idempotencyKey, Long accountId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return transactionRepository.findByIdempotencyKeyAndAccountId(idempotencyKey, accountId);
    }

    /**
     * 유니크 제약에 기대어 저장한다. 사전 조회와 저장 사이에 다른 요청이 끼어들어도
     * 두 번째는 제약에 걸려 기존 행을 돌려받는다 — 사전 조회만으로는 동시 요청을 못 막는다.
     */
    private Transaction persistIdempotent(Transaction tx, String idempotencyKey, Long accountId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return transactionRepository.save(tx);
        }
        return idempotentTransactionSaver.saveOrFetch(tx, idempotencyKey, accountId);
    }

    private static String normalizeIdempotencyKey(String key) {
        return (key != null && !key.isBlank()) ? key : null;
    }

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private void validateDailyTransferLimit(Account source, Long amount) {
        if (source.getDailyWithdrawLimit() == null && source.getDailyWithdrawCountLimit() == null) {
            return;
        }
        LocalDate koreaToday = LocalDate.now(clock.withZone(KST));
        OffsetDateTime startOfDay = koreaToday.atStartOfDay(KST).toOffsetDateTime();
        OffsetDateTime endOfDay = koreaToday.plusDays(1).atStartOfDay(KST).toOffsetDateTime();

        if (source.getDailyWithdrawLimit() != null) {
            Long todayTotal = transactionRepository.sumAmountByAccountIdAndDirectionTypeAndTransactionAtBetween(
                    source.getAccountId(), DirectionType.OUT, startOfDay, endOfDay);
            if (todayTotal + amount > source.getDailyWithdrawLimit()) {
                throw new BusinessException(ErrorCode.DAILY_TRANSFER_AMOUNT_EXCEEDED);
            }
        }

        if (source.getDailyWithdrawCountLimit() != null) {
            long todayCount = transactionRepository.countByAccountIdAndDirectionTypeAndTransactionAtBetween(
                    source.getAccountId(), DirectionType.OUT, startOfDay, endOfDay);
            if (todayCount >= source.getDailyWithdrawCountLimit()) {
                throw new BusinessException(ErrorCode.DAILY_TRANSFER_COUNT_EXCEEDED);
            }
        }
    }

    private Account getActiveAccountForUpdate(Long accountId) {
        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
        if (account.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }
        return account;
    }

    private void validateCounterpartyAccountNo(Long toAccountId, String toAccountNo, Account target) {
        if (toAccountNo != null && !toAccountNo.equals(target.getAccountNumber())) {
            throw new BusinessException(ErrorCode.INVALID_STATUS,
                    "계좌번호(" + toAccountNo + ")가 계좌 ID(" + toAccountId + ")와 일치하지 않습니다.");
        }
    }

    private String generateTxnNumber(String prefix) {
        return prefix + "-" + LocalDate.now(clock).format(DATE_FMT) + "-"
                + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }
    /**
     * 개인 메모를 고쳐 쓴다.
     *
     * <p>통장표시내용({@code transactionMemo})은 건드리지 않는다 — 이체할 때 함께
     * 보낸 문구라 사후에 바꾸면 기록이 실제와 달라진다.
     */
    @Transactional
    public Transaction updateCustomerMemo(Long transactionId, String memo) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSACTION_NOT_FOUND));
        try {
            transaction.updateCustomerMemo(memo);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_STATUS, e.getMessage());
        }
        return transaction;
    }

}
