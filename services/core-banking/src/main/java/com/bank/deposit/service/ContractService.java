package com.bank.deposit.service;

import com.bank.deposit.domain.entity.*;
import com.bank.deposit.domain.enums.*;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
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
public class ContractService {

    private final ContractRepository contractRepository;
    private final AccountRepository accountRepository;
    private final ProductRepository productRepository;
    private final ContractAppliedRateRepository appliedRateRepository;
    private final ContractSpecialTermAgreementRepository agreementRepository;
    private final TransactionRepository transactionRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    public List<Contract> findAll(String customerId, ContractStatus contractStatus) {
        if (customerId != null && contractStatus != null) {
            return contractRepository.findByCustomerIdAndContractStatus(customerId, contractStatus);
        } else if (customerId != null) {
            return contractRepository.findByCustomerId(customerId);
        } else if (contractStatus != null) {
            return contractRepository.findByContractStatus(contractStatus);
        }
        return contractRepository.findAll();
    }

    public Contract findById(Long id) {
        return contractRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));
    }

    @Transactional
    public Contract createContract(String customerId, Long productId, BigDecimal joinAmount,
                                   Integer contractPeriodMonth, JoinChannel joinChannel,
                                   BigDecimal contractInterestRate, BigDecimal totalPreferentialRate,
                                   TaxBenefitType taxBenefitType, Boolean isAutoRenewal,
                                   Boolean autoTransferEnabled, Integer autoTransferDay,
                                   Long sourceAccountId,
                                   Long branchId, Long managerId, SavingType savingType,
                                   String accountPassword) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        if (product.getProductStatus() != ProductStatus.SELLING) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_SELLING);
        }
        if (product.getMinJoinAmount() != null
                && joinAmount.compareTo(product.getMinJoinAmount()) < 0) {
            throw new BusinessException(ErrorCode.INVALID_STATUS,
                    "가입금액이 최소 가입금액보다 작습니다. (최소: " + product.getMinJoinAmount().toPlainString() + "원)");
        }
        if (product.getMaxJoinAmount() != null
                && joinAmount.compareTo(product.getMaxJoinAmount()) > 0) {
            throw new BusinessException(ErrorCode.INVALID_STATUS,
                    "가입금액이 최대 가입금액을 초과합니다. (최대: " + product.getMaxJoinAmount().toPlainString() + "원)");
        }
        if (accountPassword == null || accountPassword.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_STATUS, "계좌 비밀번호는 필수입니다.");
        }

        BigDecimal baseRate = contractInterestRate != null ? contractInterestRate : product.getBaseInterestRate();
        BigDecimal prefRate = totalPreferentialRate != null ? totalPreferentialRate : BigDecimal.ZERO;
        BigDecimal finalRate = baseRate.add(prefRate);

        LocalDate todayDate = LocalDate.now(clock);
        LocalDate maturityDate = todayDate.plusMonths(contractPeriodMonth);
        String today = todayDate.format(DATE_FMT);
        String contractNumber = "CTR-" + today + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String accountNumber = generateAccountNumber();

        Contract contract = contractRepository.save(Contract.builder()
                .contractNumber(contractNumber)
                .customerId(customerId)
                .productId(productId)
                .joinAmount(joinAmount)
                .contractInterestRate(baseRate)
                .totalPreferentialRate(prefRate)
                .finalInterestRate(finalRate)
                .taxBenefitType(taxBenefitType != null ? taxBenefitType : TaxBenefitType.GENERAL)
                .contractPeriodMonth(contractPeriodMonth)
                .startedAt(todayDate)
                .maturityAt(maturityDate)
                .joinChannel(joinChannel != null ? joinChannel : JoinChannel.WEB)
                .branchId(branchId)
                .managerId(managerId)
                .isAutoRenewal(isAutoRenewal != null && isAutoRenewal)
                .autoTransferEnabled(autoTransferEnabled != null && autoTransferEnabled)
                .autoTransferDay(autoTransferDay)
                .sourceAccountId(sourceAccountId)
                .build());

        // 비밀번호 BCrypt 해시 처리 — 평문 저장 금지
        boolean isCheckingAccount = product.getProductType() == ProductType.DEPOSIT
                && product.getProductName() != null && product.getProductName().contains("통장");
        BigDecimal initialBalance = isCheckingAccount ? joinAmount : BigDecimal.ZERO;

        accountRepository.save(Account.builder()
                .accountNumber(accountNumber)
                .customerId(customerId)
                .contractId(contract.getContractId())
                .accountType(product.getProductType())
                .savingType(savingType)
                .openedAt(todayDate)
                .maturityAt(maturityDate)
                .accountPassword(passwordEncoder.encode(accountPassword))
                .balance(initialBalance)
                .totalPaidAmount(initialBalance)
                .isOnlineBankingEnabled(true)
                .isMobileBankingEnabled(true)
                .build());

        return contract;
    }

    @Transactional
    public Contract changeStatus(Long id, ContractStatus status) {
        Contract contract = findById(id);
        contract.changeStatus(status, LocalDate.now(clock));
        return contract;
    }

    @Transactional
    public Contract terminate(Long id, String reason) {
        return terminate(id, reason, null);
    }

    @Transactional
    public Contract terminate(Long id, String reason, Long targetAccountId) {
        Contract contract = findById(id);
        if (contract.getContractStatus() != ContractStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.INVALID_STATUS, "활성 계약만 해지할 수 있습니다.");
        }
        LocalDate today = LocalDate.now(clock);
        OffsetDateTime now = OffsetDateTime.now(clock);
        contract.terminate(today, reason);

        accountRepository.findByContractId(id).ifPresent(savingsAccount -> {
            BigDecimal terminationAmount = savingsAccount.getBalance();

            if (terminationAmount.compareTo(BigDecimal.ZERO) > 0) {
                // 해지 잔액 출금 트랜잭션
                BigDecimal beforeBalance = savingsAccount.getBalance();
                savingsAccount.withdraw(terminationAmount);
                transactionRepository.save(Transaction.builder()
                        .transactionNumber("TRM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                        .accountId(savingsAccount.getAccountId())
                        .contractId(id)
                        .transactionType(TransactionType.WITHDRAW)
                        .directionType(DirectionType.OUT)
                        .amount(terminationAmount)
                        .balanceBefore(beforeBalance)
                        .balanceAfter(savingsAccount.getBalance())
                        .availableBalanceAfter(savingsAccount.getBalance())
                        .channelType(TransactionChannel.INTERNET)
                        .transactionAt(now)
                        .postedAt(now)
                        .transactionSummary("해지 출금")
                        .build());

                // 고객의 입금 계좌로 입금 (targetAccountId 지정 시 해당 계좌, 없으면 첫 번째 입출금 계좌)
                // 해지 중인 계좌(savingsAccount) 자신은 반드시 제외
                accountRepository.findByCustomerIdAndAccountStatus(contract.getCustomerId(), AccountStatus.ACTIVE)
                        .stream()
                        .filter(a -> !a.getAccountId().equals(savingsAccount.getAccountId()))
                        .filter(a -> a.getAccountType() == ProductType.DEPOSIT && Boolean.TRUE.equals(a.getIsWithdrawable()))
                        .filter(a -> targetAccountId == null || a.getAccountId().equals(targetAccountId))
                        .findFirst()
                        .ifPresent(depositAccount -> {
                            BigDecimal depositBefore = depositAccount.getBalance();
                            depositAccount.deposit(terminationAmount);
                            transactionRepository.save(Transaction.builder()
                                    .transactionNumber("TRM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                                    .accountId(depositAccount.getAccountId())
                                    .transactionType(TransactionType.DEPOSIT)
                                    .directionType(DirectionType.IN)
                                    .amount(terminationAmount)
                                    .balanceBefore(depositBefore)
                                    .balanceAfter(depositAccount.getBalance())
                                    .availableBalanceAfter(depositAccount.getBalance())
                                    .channelType(TransactionChannel.SYSTEM)
                                    .transactionAt(now)
                                    .postedAt(now)
                                    .transactionSummary("해지 입금")
                                    .counterpartyAccountId(savingsAccount.getAccountId())
                                    .build());
                        });
            }

            savingsAccount.changeStatus(AccountStatus.CLOSED, today);
        });

        return contract;
    }

    @Transactional
    public Contract mature(Long id) {
        Contract contract = findById(id);
        contract.mature(LocalDate.now(clock));
        return contract;
    }

    @Transactional
    public void updateAutoTransferDay(Long id, Integer autoTransferDay) {
        Contract contract = findById(id);
        contract.updateAutoTransferDay(autoTransferDay);
    }

    public Contract findDepositContract(Long contractId) {
        Contract contract = findById(contractId);
        Product product = productRepository.findById(contract.getProductId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "상품을 찾을 수 없습니다."));
        if (product.getProductType() != ProductType.DEPOSIT) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "수신 계약을 찾을 수 없습니다.");
        }
        return contract;
    }

    @Transactional
    public Contract updateDepositSettings(Long contractId, Boolean autoTransferEnabled, Integer autoTransferDay) {
        Contract contract = findById(contractId);
        contract.updateDepositSettings(autoTransferEnabled, autoTransferDay);
        return contract;
    }

    // ContractAppliedRates
    public List<ContractAppliedRate> findAppliedRates(Long contractId) {
        findById(contractId);
        return appliedRateRepository.findByContractId(contractId);
    }

    @Transactional
    public ContractAppliedRate saveAppliedRate(Long contractId, Long rateId, BigDecimal appliedRate, Boolean conditionVerifiedYn) {
        findById(contractId);
        return appliedRateRepository.save(ContractAppliedRate.builder()
                .contractId(contractId)
                .rateId(rateId)
                .appliedRate(appliedRate)
                .conditionVerifiedYn(conditionVerifiedYn != null && conditionVerifiedYn)
                .build());
    }

    @Transactional
    public ContractAppliedRate savePreferentialRate(Long contractId, String conditionName, BigDecimal appliedRate, Boolean appliedYn) {
        findById(contractId);
        return appliedRateRepository.save(ContractAppliedRate.builder()
                .contractId(contractId)
                .appliedRate(appliedRate)
                .conditionVerifiedYn(appliedYn != null && appliedYn)
                .build());
    }

    @Transactional
    public void deleteAppliedRate(Long appliedRateId) {
        ContractAppliedRate rate = appliedRateRepository.findById(appliedRateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "우대금리 적용 내역을 찾을 수 없습니다."));
        appliedRateRepository.delete(rate);
    }

    // SpecialTermAgreements
    public List<ContractSpecialTermAgreement> findAgreements(Long contractId) {
        findById(contractId);
        return agreementRepository.findByContractId(contractId);
    }

    @Transactional
    public ContractSpecialTermAgreement agree(Long contractId, Long specialTermId, Boolean isAgreed,
                                              String agreedAt, String ipAddress, String deviceInfo,
                                              Boolean isElectronicSigned) {
        findById(contractId);
        return agreementRepository.save(ContractSpecialTermAgreement.builder()
                .contractId(contractId)
                .specialTermId(specialTermId)
                .isAgreed(isAgreed != null && isAgreed)
                .agreedAt(agreedAt)
                .agreementIpAddress(ipAddress)
                .agreementDeviceInfo(deviceInfo)
                .isElectronicSigned(isElectronicSigned != null && isElectronicSigned)
                .build());
    }

    @Transactional
    public ContractSpecialTermAgreement withdraw(Long contractId, Long agreementId) {
        ContractSpecialTermAgreement agreement = agreementRepository.findById(agreementId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "특약 동의를 찾을 수 없습니다."));
        agreement.withdraw(LocalDate.now(clock).format(DATE_FMT));
        return agreement;
    }

    private String generateAccountNumber() {
        long sequence = accountRepository.nextAccountNumberSequenceValue();
        String body = String.format("%012d", sequence);
        return "001-" + body + calculateCheckDigit(body);
    }

    private int calculateCheckDigit(String body) {
        int sum = 0;
        boolean doubleDigit = true;
        for (int i = body.length() - 1; i >= 0; i--) {
            int digit = body.charAt(i) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return (10 - (sum % 10)) % 10;
    }
}
