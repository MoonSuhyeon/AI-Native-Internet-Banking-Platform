package com.bank.deposit.domain.entity;

import com.bank.deposit.common.BaseEntity;
import com.bank.deposit.domain.enums.ProductStatus;
import com.bank.deposit.domain.enums.ProductType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "deposit_banking_products")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Product extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "banking_product_id")
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "deposit_product_type", nullable = false)
    private ProductType productType;

    @Column(name = "deposit_product_name", length = 200, nullable = false)
    private String productName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "department_id")
    private Long departmentId;

    @Column(name = "base_interest_rate", precision = 5, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal baseInterestRate = BigDecimal.ZERO;

    @Column(name = "preferential_rate_condition", columnDefinition = "TEXT")
    private String preferentialRateCondition;

    @Column(name = "min_join_amount", precision = 18, scale = 2)
    private BigDecimal minJoinAmount;

    @Column(name = "max_join_amount", precision = 18, scale = 2)
    private BigDecimal maxJoinAmount;

    @Column(name = "min_period_month")
    private Integer minPeriodMonth;

    @Column(name = "max_period_month")
    private Integer maxPeriodMonth;

    @Column(name = "is_early_termination_allowed", nullable = false)
    @Builder.Default
    private Boolean isEarlyTerminationAllowed = false;

    @Column(name = "is_tax_benefit_available", nullable = false)
    @Builder.Default
    private Boolean isTaxBenefitAvailable = false;

    @Column(name = "is_auto_renewal_available", nullable = false)
    @Builder.Default
    private Boolean isAutoRenewalAvailable = false;

    @Column(name = "is_passbook_issued", nullable = false)
    @Builder.Default
    private Boolean isPassbookIssued = false;

    @Column(name = "released_at", columnDefinition = "CHAR(8)")
    private String releasedAt;

    @Column(name = "ended_at", columnDefinition = "CHAR(8)")
    private String endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "deposit_product_status", nullable = false)
    @Builder.Default
    private ProductStatus productStatus = ProductStatus.SELLING;

    public void changeStatus(ProductStatus status) {
        this.productStatus = status;
    }

    public void update(String productName, String description, BigDecimal baseInterestRate) {
        this.productName = productName;
        this.description = description;
        this.baseInterestRate = baseInterestRate;
    }
}
