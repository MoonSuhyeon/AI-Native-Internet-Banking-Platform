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

    @Column(name = "monthly_payment_amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal monthlyPaymentAmount;

    @Column(name = "min_monthly_payment", precision = 18, scale = 2)
    private BigDecimal minMonthlyPayment;

    @Column(name = "max_monthly_payment", precision = 18, scale = 2)
    private BigDecimal maxMonthlyPayment;

    @Column(name = "max_recognized_payment_amount", precision = 18, scale = 2)
    private BigDecimal maxRecognizedPaymentAmount;

    public void update(BigDecimal monthlyPaymentAmount, BigDecimal minMonthlyPayment, BigDecimal maxMonthlyPayment) {
        this.monthlyPaymentAmount = monthlyPaymentAmount;
        this.minMonthlyPayment = minMonthlyPayment;
        this.maxMonthlyPayment = maxMonthlyPayment;
    }
}
