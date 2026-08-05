package com.bank.deposit.domain.entity;

import com.bank.deposit.common.BaseEntity;
import com.bank.deposit.domain.enums.FailureReasonCode;
import com.bank.deposit.domain.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "deposit_payment_schedules")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PaymentSchedule extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long scheduleId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "payment_round", nullable = false)
    private Integer paymentRound;

    @Column(name = "scheduled_date", columnDefinition = "DATE", nullable = false)
    private LocalDate scheduledDate;

    @Column(name = "scheduled_amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal scheduledAmount;

    @Column(name = "is_auto_transfer", nullable = false)
    @Builder.Default
    private Boolean isAutoTransfer = false;

    /** 자동이체 출금 계좌 ID (isAutoTransfer=true일 때만 사용) */
    @Column(name = "source_account_id")
    private Long sourceAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "actual_amount", precision = 18, scale = 2)
    private BigDecimal actualAmount;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason_code", length = 50)
    private FailureReasonCode failureReasonCode;

    public void markPaid(BigDecimal amount, Long txId, OffsetDateTime paidAt) {
        this.status = PaymentStatus.PAID;
        this.actualAmount = amount;
        this.transactionId = txId;
        this.paidAt = paidAt;
    }

    public void markFailed(FailureReasonCode reason) {
        this.status = PaymentStatus.FAILED;
        this.failureReasonCode = reason;
    }

    public void markOverdue() {
        this.status = PaymentStatus.OVERDUE;
    }

    public void markSuspended() {
        this.status = PaymentStatus.SUSPENDED;
    }
}
