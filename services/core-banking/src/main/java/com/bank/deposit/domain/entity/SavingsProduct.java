package com.bank.deposit.domain.entity;

import com.bank.deposit.common.BaseEntity;
import com.bank.deposit.domain.enums.SavingType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "deposit_savings_products")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SavingsProduct extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long savingsProductId;

    @Column(name = "banking_product_id", nullable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "saving_type", nullable = false)
    private SavingType savingType;

    @Column(name = "monthly_payment_min_amount", precision = 18, scale = 2)
    private BigDecimal monthlyPaymentMinAmount;

    @Column(name = "monthly_payment_max_amount", precision = 18, scale = 2)
    private BigDecimal monthlyPaymentMaxAmount;

    public void update(SavingType savingType, BigDecimal monthlyPaymentMinAmount, BigDecimal monthlyPaymentMaxAmount) {
        this.savingType = savingType;
        this.monthlyPaymentMinAmount = monthlyPaymentMinAmount;
        this.monthlyPaymentMaxAmount = monthlyPaymentMaxAmount;
    }
}
