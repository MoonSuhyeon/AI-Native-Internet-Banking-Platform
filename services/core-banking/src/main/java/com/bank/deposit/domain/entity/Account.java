package com.bank.deposit.domain.entity;

import com.bank.deposit.common.BaseEntity;
import com.bank.deposit.domain.enums.AccountStatus;
import com.bank.deposit.domain.enums.ProductType;
import com.bank.deposit.domain.enums.SavingType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "deposit_accounts")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Account extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long accountId;

    /** 낙관적 락 — 동시 잔액 변경 시 충돌 감지. */
    @Version
    private Long version;

    @Column(name = "account_number", length = 30, nullable = false, unique = true)
    private String accountNumber;

    @Column(name = "customer_id", length = 30, nullable = false)
    private String customerId;

    @Column(name = "contract_id", nullable = false, unique = true)
    private Long contractId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private ProductType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "saving_type")
    private SavingType savingType;

    @Column(name = "bank_code", length = 10, nullable = false)
    @Builder.Default
    private String bankCode = "001";

    @Column(name = "account_alias", length = 100)
    private String accountAlias;

    @Column(name = "balance", precision = 18, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "total_paid_amount", precision = 18, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal totalPaidAmount = BigDecimal.ZERO;

    @Column(name = "total_interest_amount", precision = 18, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal totalInterestAmount = BigDecimal.ZERO;

    @Column(name = "last_transaction_at")
    private OffsetDateTime lastTransactionAt;

    @Column(name = "last_interest_paid_at")
    private OffsetDateTime lastInterestPaidAt;

    @Column(name = "currency", length = 3, nullable = false)
    @Builder.Default
    private String currency = "KRW";

    /** BCrypt 해시 값 저장. 평문 비밀번호는 저장하지 않는다. */
    @Column(name = "account_password", length = 255, nullable = false)
    private String accountPassword;

    @Column(name = "daily_withdraw_limit", precision = 18, scale = 2)
    private BigDecimal dailyWithdrawLimit;

    @Column(name = "daily_withdraw_count_limit")
    private Integer dailyWithdrawCountLimit;

    @Column(name = "atm_withdraw_limit", precision = 18, scale = 2)
    private BigDecimal atmWithdrawLimit;

    @Column(name = "fraud_flag", nullable = false)
    @Builder.Default
    private Boolean fraudFlag = false;

    @Column(name = "hold_amount", precision = 18, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal holdAmount = BigDecimal.ZERO;

    @Column(name = "is_withdrawable", nullable = false)
    @Builder.Default
    private Boolean isWithdrawable = true;

    @Column(name = "is_online_banking_enabled", nullable = false)
    @Builder.Default
    private Boolean isOnlineBankingEnabled = false;

    @Column(name = "is_mobile_banking_enabled", nullable = false)
    @Builder.Default
    private Boolean isMobileBankingEnabled = false;

    @Column(name = "is_phone_banking_enabled", nullable = false)
    @Builder.Default
    private Boolean isPhoneBankingEnabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false)
    @Builder.Default
    private AccountStatus accountStatus = AccountStatus.ACTIVE;

    @Column(name = "opened_at", columnDefinition = "DATE", nullable = false)
    private LocalDate openedAt;

    @Column(name = "maturity_at", columnDefinition = "DATE")
    private LocalDate maturityAt;

    @Column(name = "dormant_at", columnDefinition = "DATE")
    private LocalDate dormantAt;

    @Column(name = "dormant_released_at", columnDefinition = "DATE")
    private LocalDate dormantReleasedAt;

    @Column(name = "closed_at", columnDefinition = "DATE")
    private LocalDate closedAt;

    @Column(name = "status_changed_at", columnDefinition = "DATE")
    private LocalDate statusChangedAt;

    @PrePersist
    @PreUpdate
    private void validatePasswordHash() {
        if (accountPassword == null || accountPassword.isBlank()) {
            throw new IllegalStateException("Account password hash is required.");
        }
        if (!accountPassword.startsWith("$2a$")
                && !accountPassword.startsWith("$2b$")
                && !accountPassword.startsWith("$2y$")) {
            throw new IllegalStateException("Account password must be stored as a BCrypt hash.");
        }
    }

    public void deposit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
        this.lastTransactionAt = OffsetDateTime.now();
    }

    public void deposit(BigDecimal amount, Clock clock) {
        this.balance = this.balance.add(amount);
        this.lastTransactionAt = OffsetDateTime.now(clock);
    }

    public void withdraw(BigDecimal amount) {
        BigDecimal available = this.balance.subtract(this.holdAmount != null ? this.holdAmount : BigDecimal.ZERO);
        if (available.compareTo(amount) < 0) {
            throw new com.bank.deposit.exception.BusinessException(
                    com.bank.deposit.exception.ErrorCode.INSUFFICIENT_BALANCE);
        }
        this.balance = this.balance.subtract(amount);
        this.lastTransactionAt = OffsetDateTime.now();
    }

    public void withdraw(BigDecimal amount, Clock clock) {
        BigDecimal available = this.balance.subtract(this.holdAmount != null ? this.holdAmount : BigDecimal.ZERO);
        if (available.compareTo(amount) < 0) {
            throw new com.bank.deposit.exception.BusinessException(
                    com.bank.deposit.exception.ErrorCode.INSUFFICIENT_BALANCE);
        }
        this.balance = this.balance.subtract(amount);
        this.lastTransactionAt = OffsetDateTime.now(clock);
    }

    public void addInterest(BigDecimal amount) {
        this.balance = this.balance.add(amount);
        this.totalInterestAmount = this.totalInterestAmount.add(amount);
        this.lastInterestPaidAt = OffsetDateTime.now();
        this.lastTransactionAt = OffsetDateTime.now();
    }

    public void addInterest(BigDecimal amount, Clock clock) {
        this.balance = this.balance.add(amount);
        this.totalInterestAmount = this.totalInterestAmount.add(amount);
        this.lastInterestPaidAt = OffsetDateTime.now(clock);
        this.lastTransactionAt = OffsetDateTime.now(clock);
    }

    public void addPaidAmount(BigDecimal amount) {
        this.totalPaidAmount = this.totalPaidAmount.add(amount);
    }

    public void changeStatus(AccountStatus status, LocalDate statusChangedAt) {
        this.accountStatus = status;
        this.statusChangedAt = statusChangedAt;
    }

    public void updateAlias(String alias) {
        this.accountAlias = alias;
    }

    public void updateLimits(BigDecimal dailyWithdrawLimit, Integer dailyWithdrawCountLimit, BigDecimal atmWithdrawLimit) {
        this.dailyWithdrawLimit = dailyWithdrawLimit;
        this.dailyWithdrawCountLimit = dailyWithdrawCountLimit;
        this.atmWithdrawLimit = atmWithdrawLimit;
    }
}
