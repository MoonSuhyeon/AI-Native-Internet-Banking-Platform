package com.bank.deposit.service;

import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.domain.enums.AccountStatus;
import com.bank.deposit.domain.enums.ProductType;
import com.bank.deposit.domain.enums.SavingType;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public List<Account> findByCustomer(String customerId) {
        return accountRepository.findByCustomerId(customerId);
    }

    public Account findById(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
    }

    public Account findByAccountNumber(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));
    }

    public Account findActive(Long id) {
        Account account = findById(id);
        if (account.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }
        return account;
    }

    @Transactional
    public Account create(String customerId, Long contractId, ProductType accountType,
                          SavingType savingType, String accountAlias, String accountPassword) {
        if (accountRepository.findByContractId(contractId).isPresent()) {
            throw new BusinessException(ErrorCode.DUPLICATE, "이미 계좌가 생성된 계약입니다.");
        }
        if (accountPassword == null || accountPassword.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_STATUS, "Account password is required.");
        }

        LocalDate today = LocalDate.now(clock);
        String accountNumber = generateAccountNumber();

        return accountRepository.save(Account.builder()
                .accountNumber(accountNumber)
                .customerId(customerId)
                .contractId(contractId)
                .accountType(accountType)
                .savingType(savingType)
                .accountAlias(accountAlias)
                .accountPassword(passwordEncoder.encode(accountPassword))
                .openedAt(today)
                .build());
    }

    @Transactional
    public Account changeStatus(Long id, AccountStatus status) {
        Account account = findById(id);
        account.changeStatus(status, LocalDate.now(clock));
        return account;
    }

    @Transactional
    public Account updateLimits(Long id, BigDecimal dailyWithdrawLimit,
                                Integer dailyWithdrawCountLimit, BigDecimal atmWithdrawLimit) {
        Account account = findById(id);
        account.updateLimits(dailyWithdrawLimit, dailyWithdrawCountLimit, atmWithdrawLimit);
        return account;
    }

    @Transactional
    public Account updateAlias(Long id, String alias) {
        Account account = findById(id);
        account.updateAlias(alias);
        return account;
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
