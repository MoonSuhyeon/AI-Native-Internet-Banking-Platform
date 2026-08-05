package com.bank.deposit.domain.entity;

import com.bank.deposit.domain.enums.RecognitionStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "deposit_subscription_payment_recognition_history")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
public class SubscriptionPaymentRecognitionHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long recognitionId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "payment_amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal paymentAmount;

    @Column(name = "recognized_amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal recognizedAmount;

    /** 납입 월 (YYYYMM 형식 6자리 문자열, DB: VARCHAR(6)) */
    @Column(name = "payment_month", nullable = false, length = 6)
    private String paymentMonth;

    @Column(name = "recognized_at")
    private OffsetDateTime recognizedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "recognition_status", nullable = false)
    @Builder.Default
    private RecognitionStatus recognitionStatus = RecognitionStatus.PENDING;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
