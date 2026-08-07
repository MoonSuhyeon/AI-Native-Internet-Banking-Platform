package com.bank.deposit.domain.entity;

import com.bank.deposit.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "deposit_subscription_products")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SubscriptionProduct extends BaseEntity {

    @Id
    @Column(name = "banking_product_id")
    private Long productId;

    @Column(name = "monthly_payment_amount", nullable = false)
    private Long monthlyPaymentAmount;

    @Column(name = "min_monthly_payment")
    private Long minMonthlyPayment;

    @Column(name = "max_monthly_payment")
    private Long maxMonthlyPayment;

    @Column(name = "max_recognized_payment_amount")
    private Long maxRecognizedPaymentAmount;

    public void update(Long monthlyPaymentAmount, Long minMonthlyPayment, Long maxMonthlyPayment) {
        this.monthlyPaymentAmount = monthlyPaymentAmount;
        this.minMonthlyPayment = minMonthlyPayment;
        this.maxMonthlyPayment = maxMonthlyPayment;
    }
}
