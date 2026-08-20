package com.bank.deposit.service;

import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.domain.entity.InterestHistory;
import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.domain.enums.*;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.repository.AccountRepository;
import com.bank.deposit.repository.ContractRepository;
import com.bank.deposit.dto.response.InterestIncomeStatementResponse;
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
import java.util.ArrayList;
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

    /**
     * 이자소득 원천징수영수증 — 화면의 "연말정산증명서".
     *
     * <p>고객의 <b>모든 계좌</b>를 한 해치로 합친다. 연말정산에 적는 값이 합계이기
     * 때문이다. 계좌별 내역도 함께 담는다 — 합계만 주면 숫자가 이상할 때 어디서
     * 왔는지 확인할 길이 없다.
     *
     * <p>귀속연도는 <b>이자를 받은 날</b>(interest_paid_at)로 가른다. 계산 기간이
     * 해를 걸치더라도 소득은 받은 해에 잡힌다 — 계산 시작일로 가르면 12월에 시작해
     * 1월에 받은 이자가 전년도로 들어가 두 해의 합계가 모두 틀어진다.
     */
    public InterestIncomeStatementResponse interestIncomeStatement(String customerId, int taxYear) {
        List<InterestIncomeStatementResponse.AccountLine> lines = new ArrayList<>();

        for (Account account : accountRepository.findByCustomerId(customerId)) {
            List<InterestHistory> yearly = interestHistoryRepository
                    .findByAccountIdOrderByInterestPaidAtDesc(account.getAccountId()).stream()
                    .filter(h -> h.getInterestPaidAt() != null)
                    .filter(h -> h.getInterestPaidAt().getYear() == taxYear)
                    .toList();

            if (yearly.isEmpty()) {
                // 이자를 받지 않은 계좌는 영수증에 올리지 않는다. 0 원 줄이 늘어나면
                // 정작 봐야 할 줄이 묻힌다.
                continue;
            }

            lines.add(new InterestIncomeStatementResponse.AccountLine(
                    account.getAccountId(),
                    account.getAccountNumber(),
                    account.getAccountAlias(),
                    yearly.stream().mapToLong(InterestHistory::getInterestBeforeTax).sum(),
                    yearly.stream().mapToLong(InterestHistory::getInterestTaxAmount).sum(),
                    yearly.stream().mapToLong(InterestHistory::getLocalIncomeTaxAmount).sum(),
                    yearly.stream().mapToLong(InterestHistory::getInterestAfterTax).sum(),
                    yearly.size()));
        }

        return InterestIncomeStatementResponse.of(taxYear, lines);
    }

    public Long sumByContract(Long contractId) {
        return interestHistoryRepository.sumInterestAfterTaxByContractId(contractId);
    }

    @Transactional
    public InterestHistory payInterest(Long contractId, Long accountId, Long interestBeforeTax,
                                       Long interestTaxAmount, Long localIncomeTaxAmount,
                                       BigDecimal appliedInterestRate, TaxBenefitType taxBenefitType,
                                       BigDecimal appliedTaxRate, InterestReason interestReason,
                                       String calcStartDate, String calcEndDate) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        long interestAfterTax = interestBeforeTax
                - (interestTaxAmount != null ? interestTaxAmount : 0L)
                - (localIncomeTaxAmount != null ? localIncomeTaxAmount : 0L);

        long interestAmount = interestAfterTax;
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
                .interestTaxAmount(interestTaxAmount != null ? interestTaxAmount : 0L)
                .localIncomeTaxAmount(localIncomeTaxAmount != null ? localIncomeTaxAmount : 0L)
                .interestAfterTax(interestAfterTax)
                .interestReason(interestReason != null ? interestReason : InterestReason.REGULAR_INTEREST)
                .interestOccurredAt(now)
                .interestPaidAt(now)
                .interestCalculationStartDate(calcStartDate)
                .interestCalculationEndDate(calcEndDate)
                .build());

        long before = account.getBalance() - interestAmount;
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
