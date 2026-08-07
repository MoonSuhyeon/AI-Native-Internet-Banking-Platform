package com.bank.loan.product.domain;

import com.bank.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 대출 상품 마스터. ERD STAGE 1 LOAN_PRODUCT 매핑.
 */
@Getter
@Entity
@Table(name = "loan_product")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class LoanProduct extends BaseEntity {

    public static final String STATUS_DRAFT         = "DRAFT";
    public static final String STATUS_ACTIVE        = "ACTIVE";
    public static final String STATUS_DISCONTINUED  = "DISCONTINUED";


    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "prod_id")
    private Long prodId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "prod_cd", nullable = false, length = 30, unique = true)
    private String prodCd;

    @Column(name = "prod_name", nullable = false, length = 200)
    private String prodName;

    @Column(name = "loan_type_cd", nullable = false, length = 50)
    private String loanTypeCd;

    @Column(name = "target_customer_cd", length = 50)
    private String targetCustomerCd;

    @Column(name = "repayment_method_cd", nullable = false, length = 50)
    private String repaymentMethodCd;

    @Column(name = "rate_type_cd", nullable = false, length = 50)
    private String rateTypeCd;

    @Column(name = "base_rate_bps", nullable = false)
    private Integer baseRateBps;

    @Column(name = "min_rate_bps")
    private Integer minRateBps;

    @Column(name = "max_rate_bps")
    private Integer maxRateBps;

    @Column(name = "min_amount", nullable = false)
    private Long minAmount;

    @Column(name = "max_amount", nullable = false)
    private Long maxAmount;

    @Column(name = "min_period_mo", nullable = false)
    private Integer minPeriodMo;

    @Column(name = "max_period_mo", nullable = false)
    private Integer maxPeriodMo;

    @Column(name = "collateral_required_yn", nullable = false)
    private Boolean collateralRequiredYn;

    @Column(name = "guarantor_required_yn", nullable = false)
    private Boolean guarantorRequiredYn;

    /**
     * 보증 필수 상품에서 요구되는 최소 SIGNED 보증인 수.
     * guarantorRequiredYn = true 이면 반드시 1 이상 — 서비스 레이어에서 강제.
     */
    @Column(name = "min_guarantor_count", nullable = false)
    private Integer minGuarantorCount;

    /**
     * 승인 유효기간(일). NULL 이면 시스템 기본값 14일 적용.
     * 1~90 범위 — 서비스 레이어에서 강제.
     */
    @Column(name = "application_validity_days")
    private Integer applicationValidityDays;

    /** 담보 필수 상품 여부. 본심사 시 활성 담보별 LTV PASS 검증의 트리거. */
    public boolean isCollateralRequired() {
        return Boolean.TRUE.equals(collateralRequiredYn);
    }

    /** 보증 필수 상품 여부. GuarantorPolicyValidator 가 사전조건 검증에 사용. */
    public boolean isGuarantorRequired() {
        return Boolean.TRUE.equals(guarantorRequiredYn);
    }

    @Column(name = "sale_start_date", length = 8)
    private String saleStartDate;

    @Column(name = "sale_end_date", length = 8)
    private String saleEndDate;

    @Column(name = "prod_status_cd", nullable = false, length = 50)
    private String prodStatusCd;

    @Column(name = "prod_terms_url", length = 500)
    private String prodTermsUrl;

    @Column(name = "prod_terms_hash", length = 128)
    private String prodTermsHash;

    /**
     * 부분 수정. 각 인자 null 이면 해당 필드는 변경하지 않는다.
     * prodCd / productId 는 식별자라 본 메서드에서 변경 불가.
     */
    public void update(
            String prodName,
            String loanTypeCd, String targetCustomerCd,
            String repaymentMethodCd, String rateTypeCd,
            Integer baseRateBps, Integer minRateBps, Integer maxRateBps,
            Long minAmount, Long maxAmount,
            Integer minPeriodMo, Integer maxPeriodMo,
            Boolean collateralRequiredYn, Boolean guarantorRequiredYn,
            Integer minGuarantorCount,
            Integer applicationValidityDays,
            String saleStartDate, String saleEndDate,
            String prodTermsUrl, String prodTermsHash,
            String prodStatusCd
    ) {
        if (prodName != null) this.prodName = prodName;
        if (loanTypeCd != null) this.loanTypeCd = loanTypeCd;
        if (targetCustomerCd != null) this.targetCustomerCd = targetCustomerCd;
        if (repaymentMethodCd != null) this.repaymentMethodCd = repaymentMethodCd;
        if (rateTypeCd != null) this.rateTypeCd = rateTypeCd;
        if (baseRateBps != null) this.baseRateBps = baseRateBps;
        if (minRateBps != null) this.minRateBps = minRateBps;
        if (maxRateBps != null) this.maxRateBps = maxRateBps;
        if (minAmount != null) this.minAmount = minAmount;
        if (maxAmount != null) this.maxAmount = maxAmount;
        if (minPeriodMo != null) this.minPeriodMo = minPeriodMo;
        if (maxPeriodMo != null) this.maxPeriodMo = maxPeriodMo;
        if (collateralRequiredYn != null) this.collateralRequiredYn = collateralRequiredYn;
        if (guarantorRequiredYn != null) this.guarantorRequiredYn = guarantorRequiredYn;
        if (minGuarantorCount != null) this.minGuarantorCount = minGuarantorCount;
        if (applicationValidityDays != null) this.applicationValidityDays = applicationValidityDays;
        if (saleStartDate != null) this.saleStartDate = saleStartDate;
        if (saleEndDate != null) this.saleEndDate = saleEndDate;
        if (prodTermsUrl != null) this.prodTermsUrl = prodTermsUrl;
        if (prodTermsHash != null) this.prodTermsHash = prodTermsHash;
        if (prodStatusCd != null) this.prodStatusCd = prodStatusCd;
    }

    public boolean isDiscontinued() {
        return STATUS_DISCONTINUED.equals(this.prodStatusCd);
    }

    /** 상태를 DISCONTINUED 로 전이하고 판매 종료일을 기록한다. */
    public void discontinue(String saleEndDate) {
        this.prodStatusCd = STATUS_DISCONTINUED;
        this.saleEndDate = saleEndDate;
    }

    /** STATUS_DRAFT 등 외부에서 직전 상태 비교 시 사용. */
    public String currentStatus() {
        return this.prodStatusCd;
    }
}
