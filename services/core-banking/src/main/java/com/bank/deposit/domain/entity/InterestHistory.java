package com.bank.deposit.domain.entity;

import com.bank.deposit.common.BaseEntity;
import com.bank.deposit.domain.enums.InterestReason;
import com.bank.deposit.domain.enums.TaxBenefitType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "deposit_interest_history")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class InterestHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long interestId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "applied_interest_rate", precision = 5, scale = 2, nullable = false)
    private BigDecimal appliedInterestRate;

    @Column(name = "interest_calculation_start_date", columnDefinition = "CHAR(8)")
    private String interestCalculationStartDate;

    @Column(name = "interest_calculation_end_date", columnDefinition = "CHAR(8)")
    private String interestCalculationEndDate;

    @Column(name = "interest_occurred_at")
    private OffsetDateTime interestOccurredAt;

    @Column(name = "interest_amount", precision = 18, scale = 2, nullable = false)
    private BigDecimal interestAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_benefit_type", nullable = false)
    private TaxBenefitType taxBenefitType;

    @Column(name = "applied_tax_rate", precision = 6, scale = 4, nullable = false)
    private BigDecimal appliedTaxRate;

    @Column(name = "interest_before_tax", precision = 18, scale = 2, nullable = false)
    private BigDecimal interestBeforeTax;

    @Column(name = "interest_tax_amount", precision = 18, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal interestTaxAmount = BigDecimal.ZERO;

    @Column(name = "local_income_tax_amount", precision = 18, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal localIncomeTaxAmount = BigDecimal.ZERO;

    @Column(name = "interest_after_tax", precision = 18, scale = 2, nullable = false)
    private BigDecimal interestAfterTax;

    @Enumerated(EnumType.STRING)
    @Column(name = "interest_reason", nullable = false)
    private InterestReason interestReason;

    @Column(name = "interest_paid_at", nullable = false)
    private OffsetDateTime interestPaidAt;
}
