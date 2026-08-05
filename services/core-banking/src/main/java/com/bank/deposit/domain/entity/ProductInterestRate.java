package com.bank.deposit.domain.entity;

import com.bank.deposit.common.BaseEntity;
import com.bank.deposit.domain.enums.RateType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "banking_deposit_product_interest_rates")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ProductInterestRate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long rateId;

    @Column(name = "banking_product_id", nullable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_type", nullable = false)
    private RateType rateType;

    @Column(name = "minimum_contract_period")
    private Integer minimumContractPeriod;

    @Column(name = "maximum_contract_period")
    private Integer maximumContractPeriod;

    @Column(name = "minimum_join_amount", precision = 18, scale = 2)
    private BigDecimal minimumJoinAmount;

    @Column(name = "maximum_join_amount", precision = 18, scale = 2)
    private BigDecimal maximumJoinAmount;

    @Column(name = "rate", precision = 5, scale = 2, nullable = false)
    private BigDecimal rate;

    @Column(name = "condition_description", columnDefinition = "TEXT")
    private String conditionDescription;

    @Column(name = "effective_start_date", columnDefinition = "CHAR(8)", nullable = false)
    private String effectiveStartDate;

    @Column(name = "effective_end_date", columnDefinition = "CHAR(8)")
    private String effectiveEndDate;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    public void expire() {
        this.isActive = false;
    }

    public void update(BigDecimal rate, String effectiveEndDate) {
        this.rate = rate;
        this.effectiveEndDate = effectiveEndDate;
    }
}
