package com.bank.deposit.service;

import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.domain.entity.InterestHistory;
import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.domain.enums.*;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.repository.AccountRepository;
import com.bank.deposit.repository.ContractRepository;
import com.bank.deposit.repository.InterestHistoryRepository;
import com.bank.deposit.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InterestService {

    private final InterestHistoryRepository interestHistoryRepository;
    private final AccountRepository accountRepository;
    private final ContractRepository contractRepository;
    private final TransactionRepository transactionRepository;
    private final Clock clock;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    public List<InterestHistory> findByContract(Long contractId) {
        return interestHistoryRepository.findByContractIdOrderByInterestPaidAtDesc(contractId);
    }

    public InterestHistory findById(Long id) {
        return interestHistoryRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "이자 이력을 찾을 수 없습니다."));
    }

    public BigDecimal sumByContract(Long contractId) {
        return interestHistoryRepository.sumInterestAfterTaxByContractId(contractId);
    }

    @Transactional
    public InterestHistory payInterest(Long contractId, Long accountId, BigDecimal interestBeforeTax,
                                       BigDecimal interestTaxAmount, BigDecimal localIncomeTaxAmount,
                                       BigDecimal appliedInterestRate, TaxBenefitType taxBenefitType,
                                       BigDecimal appliedTaxRate, InterestReason interestReason,
                                       String calcStartDate, String calcEndDate) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        BigDecimal interestAfterTax = interestBeforeTax
                .subtract(interestTaxAmount != null ? interestTaxAmount : BigDecimal.ZERO)
                .subtract(localIncomeTaxAmount != null ? localIncomeTaxAmount : BigDecimal.ZERO);

        BigDecimal interestAmount = interestAfterTax;
        account.addInterest(interestAmount, clock);

        OffsetDateTime now = OffsetDateTime.now(clock);
        InterestHistory history = interestHistoryRepository.save(InterestHistory.builder()
                .contractId(contractId)
                .accountId(accountId)
                .appliedInterestRate(appliedInterestRate)
                .taxBenefitType(taxBenefitType != null ? taxBenefitType : TaxBenefitType.GENERAL)
                .appliedTaxRate(appliedTaxRate != null ? appliedTaxRate : new BigDecimal("0.154"))
                .interestAmount(interestAmount)
                .interestBeforeTax(interestBeforeTax)
                .interestTaxAmount(interestTaxAmount != null ? interestTaxAmount : BigDecimal.ZERO)
                .localIncomeTaxAmount(localIncomeTaxAmount != null ? localIncomeTaxAmount : BigDecimal.ZERO)
                .interestAfterTax(interestAfterTax)
                .interestReason(interestReason != null ? interestReason : InterestReason.REGULAR_INTEREST)
                .interestOccurredAt(now)
                .interestPaidAt(now)
                .interestCalculationStartDate(calcStartDate)
                .interestCalculationEndDate(calcEndDate)
                .build());

        BigDecimal before = account.getBalance().subtract(interestAmount);
        transactionRepository.save(Transaction.builder()
                .transactionNumber("INT-" + LocalDate.now(clock).format(DATE_FMT) + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .accountId(accountId)
                .contractId(contractId)
                .transactionType(TransactionType.INTEREST)
                .directionType(DirectionType.IN)
                .amount(interestAmount)
                .balanceBefore(before)
                .balanceAfter(account.getBalance())
                .availableBalanceAfter(account.getBalance())
                .channelType(TransactionChannel.SYSTEM)
                .transactionAt(now)
                .postedAt(now)
                .transactionSummary("이자 지급")
                .build());

        return history;
    }
}
