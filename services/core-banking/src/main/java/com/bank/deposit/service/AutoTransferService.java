package com.bank.deposit.service;

import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.domain.entity.Contract;
import com.bank.deposit.domain.entity.PaymentSchedule;
import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.domain.enums.*;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.repository.AccountRepository;
import com.bank.deposit.repository.ContractRepository;
import com.bank.deposit.repository.PaymentScheduleRepository;
import com.bank.deposit.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoTransferService {

    private final PaymentScheduleRepository scheduleRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final ContractRepository contractRepository;
    private final Clock clock;

    private static final int MAX_CONSECUTIVE_MISS = 3;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 자동이체 실행.
     * 출금 계좌 잔액 부족 등 실패 시 schedule을 FAILED로 변경하고 연속 실패 횟수를 증가시킨다.
     * 3회 연속 실패 시 자동이체를 정지하고 계약 상태를 SUSPENDED로 변경한다.
     */
    @Transactional
    public void executeAutoTransfer(PaymentSchedule schedule) {
        Contract contract = contractRepository.findById(schedule.getContractId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));

        Long sourceId = schedule.getSourceAccountId();
        Long targetId = schedule.getAccountId();
        BigDecimal amount = schedule.getScheduledAmount();

        if (sourceId == null) {
            log.warn("[AutoTransfer] contractId={} round={}: sourceAccountId 미설정 — FAILED 처리",
                    schedule.getContractId(), schedule.getPaymentRound());
            schedule.markFailed(FailureReasonCode.INVALID_ACCOUNT);
            handleMiss(contract, schedule);
            return;
        }

        try {
            Account source, target;
            if (sourceId < targetId) {
                source = getActiveAccountForUpdate(sourceId);
                target = getActiveAccountForUpdate(targetId);
            } else {
                target = getActiveAccountForUpdate(targetId);
                source = getActiveAccountForUpdate(sourceId);
            }

            BigDecimal srcBefore = source.getBalance();
            source.withdraw(amount, clock);
            OffsetDateTime now = OffsetDateTime.now(clock);

            Transaction outTx = transactionRepository.save(Transaction.builder()
                    .transactionNumber(generateTxnNumber("ATF"))
                    .accountId(sourceId)
                    .contractId(contract.getContractId())
                    .transactionType(TransactionType.TRANSFER)
                    .directionType(DirectionType.OUT)
                    .amount(amount)
                    .balanceBefore(srcBefore)
                    .balanceAfter(source.getBalance())
                    .availableBalanceAfter(source.getBalance())
                    .transferType(TransferType.INTERNAL)
                    .counterpartyAccountId(targetId)
                    .channelType(TransactionChannel.SYSTEM)
                    .paymentRound(schedule.getPaymentRound())
                    .transactionAt(now)
                    .postedAt(now)
                    .transactionSummary("자동이체 출금")
                    .build());

            BigDecimal tgtBefore = target.getBalance();
            target.deposit(amount, clock);
            target.addPaidAmount(amount);

            transactionRepository.save(Transaction.builder()
                    .transactionNumber(generateTxnNumber("ATF"))
                    .accountId(targetId)
                    .contractId(contract.getContractId())
                    .transactionType(TransactionType.SAVINGS_PAYMENT)
                    .directionType(DirectionType.IN)
                    .amount(amount)
                    .balanceBefore(tgtBefore)
                    .balanceAfter(target.getBalance())
                    .availableBalanceAfter(target.getBalance())
                    .channelType(TransactionChannel.SYSTEM)
                    .paymentRound(schedule.getPaymentRound())
                    .transactionAt(now)
                    .postedAt(now)
                    .transactionSummary("자동이체 적금 납입")
                    .build());

            schedule.markPaid(amount, outTx.getTransactionId(), now);
            contract.resetMissCount();
            log.info("[AutoTransfer] 성공 contractId={} round={} amount={}",
                    contract.getContractId(), schedule.getPaymentRound(), amount);

        } catch (BusinessException e) {
            FailureReasonCode reason = mapToFailureReason(e.getErrorCode());
            schedule.markFailed(reason);
            handleMiss(contract, schedule);
            log.warn("[AutoTransfer] 실패 contractId={} round={} reason={}",
                    contract.getContractId(), schedule.getPaymentRound(), reason);
        }
    }

    /**
     * 수동 납입 지연 처리.
     * 예정일을 지나도 납입하지 않은 수동 납입 스케줄을 OVERDUE로 변경하고
     * 연속 미납 횟수를 증가시킨다.
     */
    @Transactional
    public void markManualOverdue(PaymentSchedule schedule) {
        Contract contract = contractRepository.findById(schedule.getContractId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
        schedule.markOverdue();
        handleMiss(contract, schedule);
        log.warn("[ManualPayment] 납입 지연 contractId={} round={} scheduledDate={}",
                contract.getContractId(), schedule.getPaymentRound(), schedule.getScheduledDate());
    }

    /**
     * 수동 납입 처리 (고객이 직접 납입).
     * PENDING 또는 OVERDUE 상태의 스케줄에 대해 실행 가능하다.
     */
    @Transactional
    public PaymentSchedule executeManualPayment(Long scheduleId, Long sourceAccountId) {
        PaymentSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "납입 스케줄을 찾을 수 없습니다."));

        if (schedule.getStatus() == PaymentStatus.PAID
                || schedule.getStatus() == PaymentStatus.SUSPENDED) {
            throw new BusinessException(ErrorCode.INVALID_STATUS, "이미 납입 완료되었거나 정지된 스케줄입니다.");
        }

        Contract contract = contractRepository.findById(schedule.getContractId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));

        BigDecimal amount = schedule.getScheduledAmount();
        Long targetId = schedule.getAccountId();

        Account source, target;
        if (sourceAccountId < targetId) {
            source = getActiveAccountForUpdate(sourceAccountId);
            target = getActiveAccountForUpdate(targetId);
        } else {
            target = getActiveAccountForUpdate(targetId);
            source = getActiveAccountForUpdate(sourceAccountId);
        }

        BigDecimal srcBefore = source.getBalance();
        source.withdraw(amount, clock);
        OffsetDateTime now = OffsetDateTime.now(clock);

        Transaction outTx = transactionRepository.save(Transaction.builder()
                .transactionNumber(generateTxnNumber("MNP"))
                .accountId(sourceAccountId)
                .contractId(contract.getContractId())
                .transactionType(TransactionType.TRANSFER)
                .directionType(DirectionType.OUT)
                .amount(amount)
                .balanceBefore(srcBefore)
                .balanceAfter(source.getBalance())
                .availableBalanceAfter(source.getBalance())
                .transferType(TransferType.INTERNAL)
                .counterpartyAccountId(targetId)
                .channelType(TransactionChannel.INTERNET)
                .paymentRound(schedule.getPaymentRound())
                .transactionAt(now)
                .postedAt(now)
                .transactionSummary("적금 납입")
                .build());

        BigDecimal tgtBefore = target.getBalance();
        target.deposit(amount, clock);
        target.addPaidAmount(amount);

        transactionRepository.save(Transaction.builder()
                .transactionNumber(generateTxnNumber("MNP"))
                .accountId(targetId)
                .contractId(contract.getContractId())
                .transactionType(TransactionType.SAVINGS_PAYMENT)
                .directionType(DirectionType.IN)
                .amount(amount)
                .balanceBefore(tgtBefore)
                .balanceAfter(target.getBalance())
                .availableBalanceAfter(target.getBalance())
                .channelType(TransactionChannel.SYSTEM)
                .paymentRound(schedule.getPaymentRound())
                .transactionAt(now)
                .postedAt(now)
                .transactionSummary("적금 납입 수신")
                .build());

        schedule.markPaid(amount, outTx.getTransactionId(), now);
        contract.resetMissCount();

        // 납입으로 계약이 SUSPENDED 상태였다면 ACTIVE로 복구
        if (contract.getContractStatus() == ContractStatus.SUSPENDED) {
            contract.changeStatus(ContractStatus.ACTIVE, LocalDate.now(clock));
        }

        return schedule;
    }

    private void handleMiss(Contract contract, PaymentSchedule schedule) {
        contract.incrementMissCount();
        if (contract.getConsecutiveMissCount() >= MAX_CONSECUTIVE_MISS) {
            contract.suspendAutoTransfer(LocalDate.now(clock));
            schedule.markSuspended();
            log.error("[Payment] 연속 {}회 실패 — 자동이체 정지 contractId={}",
                    MAX_CONSECUTIVE_MISS, contract.getContractId());
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

    private FailureReasonCode mapToFailureReason(ErrorCode errorCode) {
        return switch (errorCode) {
            case INSUFFICIENT_BALANCE -> FailureReasonCode.INSUFFICIENT_BALANCE;
            case ACCOUNT_NOT_ACTIVE, ACCOUNT_NOT_FOUND -> FailureReasonCode.INVALID_ACCOUNT;
            default -> FailureReasonCode.SYSTEM_ERROR;
        };
    }

    private String generateTxnNumber(String prefix) {
        return prefix + "-" + LocalDate.now(clock).format(DATE_FMT) + "-"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
