package com.bank.deposit.domain.entity;

import com.bank.deposit.common.BaseEntity;
import com.bank.deposit.domain.enums.*;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;

@Entity
@Table(name = "deposit_transactions")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Transaction extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long transactionId;

    @Column(name = "transaction_number", length = 50, nullable = false, unique = true)
    private String transactionNumber;

    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "contract_id")
    private Long contractId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private TransactionType transactionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction_type", nullable = false)
    private DirectionType directionType;

    @Column(name = "amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "balance_before", precision = 18, scale = 2, nullable = false)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", precision = 18, scale = 2, nullable = false)
    private BigDecimal balanceAfter;

    @Column(name = "available_balance_after", precision = 18, scale = 2)
    private BigDecimal availableBalanceAfter;

    @Column(name = "fee_amount", precision = 18, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal feeAmount = BigDecimal.ZERO;

    @Column(name = "currency", length = 3, nullable = false)
    @Builder.Default
    private String currency = "KRW";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private TransactionStatus status = TransactionStatus.SUCCESS;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false)
    private TransactionChannel channelType;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "terminal_id", length = 50)
    private String terminalId;

    @Column(name = "transaction_location", length = 100)
    private String transactionLocation;

    @Column(name = "transaction_memo", length = 255)
    private String transactionMemo;

    @Column(name = "transaction_summary", length = 100)
    private String transactionSummary;

    @Column(name = "transaction_at", nullable = false)
    private OffsetDateTime transactionAt;

    @Column(name = "posted_at")
    private OffsetDateTime postedAt;

    @Column(name = "canceled_at")
    private OffsetDateTime canceledAt;

    @Column(name = "depositor_customer_id", length = 30)
    private String depositorCustomerId;

    @Column(name = "depositor_name", length = 100)
    private String depositorName;

    @Column(name = "delegate_customer_id", length = 30)
    private String delegateCustomerId;

    @Column(name = "delegate_customer_name", length = 100)
    private String delegateCustomerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "transfer_type")
    private TransferType transferType;

    @Column(name = "counterparty_bank_code", length = 10)
    private String counterpartyBankCode;

    @Column(name = "counterparty_bank_name", length = 100)
    private String counterpartyBankName;

    @Column(name = "counterparty_account_no", length = 30)
    private String counterpartyAccountNo;

    @Column(name = "counterparty_account_id")
    private Long counterpartyAccountId;

    @Column(name = "counterparty_customer_id", length = 30)
    private String counterpartyCustomerId;

    @Column(name = "counterparty_name", length = 100)
    private String counterpartyName;

    @Column(name = "counterparty_name_verified_yn")
    private Boolean counterpartyNameVerifiedYn;

    @Column(name = "transfer_requested_at")
    private OffsetDateTime transferRequestedAt;

    @Column(name = "transfer_completed_at")
    private OffsetDateTime transferCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method")
    private PaymentMethod paymentMethod;

    @Column(name = "merchant_id", length = 50)
    private String merchantId;

    @Column(name = "merchant_name", length = 100)
    private String merchantName;

    @Column(name = "approval_number", length = 50)
    private String approvalNumber;

    @Column(name = "external_transaction_no", length = 100)
    private String externalTransactionNo;

    @Column(name = "payment_round")
    private Integer paymentRound;

    @Column(name = "original_transaction_id")
    private Long originalTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_type")
    private FailureType failureType;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason_code")
    private FailureReasonCode failureReasonCode;

    @Column(name = "failure_at")
    private OffsetDateTime failureAt;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    public void cancel() {
        this.status = TransactionStatus.CANCELED;
        this.canceledAt = OffsetDateTime.now();
    }

    public void cancel(Clock clock) {
        this.status = TransactionStatus.CANCELED;
        this.canceledAt = OffsetDateTime.now(clock);
    }
}
