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

    @Column(name = "balance", nullable = false)
    @Builder.Default
    private Long balance = 0L;

    @Column(name = "total_paid_amount", nullable = false)
    @Builder.Default
    private Long totalPaidAmount = 0L;

    @Column(name = "total_interest_amount", nullable = false)
    @Builder.Default
    private Long totalInterestAmount = 0L;

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

    @Column(name = "daily_withdraw_limit")
    private Long dailyWithdrawLimit;

    @Column(name = "daily_withdraw_count_limit")
    private Integer dailyWithdrawCountLimit;

    @Column(name = "atm_withdraw_limit")
    private Long atmWithdrawLimit;

    @Column(name = "fraud_flag", nullable = false)
    @Builder.Default
    private Boolean fraudFlag = false;

    @Column(name = "hold_amount", nullable = false)
    @Builder.Default
    private Long holdAmount = 0L;

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

    public void deposit(Long amount) {
        this.balance += amount;
        this.lastTransactionAt = OffsetDateTime.now();
    }

    public void deposit(Long amount, Clock clock) {
        this.balance += amount;
        this.lastTransactionAt = OffsetDateTime.now(clock);
    }

    public void withdraw(Long amount) {
        assertWithdrawable(amount);
        this.balance -= amount;
        this.lastTransactionAt = OffsetDateTime.now();
    }

    public void withdraw(Long amount, Clock clock) {
        assertWithdrawable(amount);
        this.balance -= amount;
        this.lastTransactionAt = OffsetDateTime.now(clock);
    }

    /** 출금가능액 = 잔액 - 지급정지액. 두 withdraw 오버로드가 같은 판정을 쓰게 모은다. */
    private void assertWithdrawable(long amount) {
        long available = this.balance - (this.holdAmount != null ? this.holdAmount : 0L);
        if (available < amount) {
            throw new com.bank.deposit.exception.BusinessException(
                    com.bank.deposit.exception.ErrorCode.INSUFFICIENT_BALANCE);
        }
    }

    public void addInterest(Long amount) {
        this.balance += amount;
        this.totalInterestAmount += amount;
        this.lastInterestPaidAt = OffsetDateTime.now();
        this.lastTransactionAt = OffsetDateTime.now();
    }

    public void addInterest(Long amount, Clock clock) {
        this.balance += amount;
        this.totalInterestAmount += amount;
        this.lastInterestPaidAt = OffsetDateTime.now(clock);
        this.lastTransactionAt = OffsetDateTime.now(clock);
    }

    public void addPaidAmount(Long amount) {
        this.totalPaidAmount += amount;
    }

    public void changeStatus(AccountStatus status, LocalDate statusChangedAt) {
        this.accountStatus = status;
        this.statusChangedAt = statusChangedAt;
    }

    public void updateAlias(String alias) {
        this.accountAlias = alias;
    }

    public void updateLimits(Long dailyWithdrawLimit, Integer dailyWithdrawCountLimit, Long atmWithdrawLimit) {
        this.dailyWithdrawLimit = dailyWithdrawLimit;
        this.dailyWithdrawCountLimit = dailyWithdrawCountLimit;
        this.atmWithdrawLimit = atmWithdrawLimit;
    }
}
