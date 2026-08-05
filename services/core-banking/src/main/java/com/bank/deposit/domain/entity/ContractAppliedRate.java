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
@Table(name = "deposit_contract_applied_rates")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ContractAppliedRate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long appliedRateId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "rate_id")
    private Long rateId;

    @Column(name = "applied_rate", precision = 5, scale = 2, nullable = false)
    private BigDecimal appliedRate;

    @Column(name = "condition_verified_yn", nullable = false)
    @Builder.Default
    private Boolean conditionVerifiedYn = false;
}
