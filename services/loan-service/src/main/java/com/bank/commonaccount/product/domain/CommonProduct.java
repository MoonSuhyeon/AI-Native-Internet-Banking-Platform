package com.bank.commonaccount.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 공통 상품 마스터 (common_db.common_product).
 *
 * 수신/여신 상품이 공유하는 상품 마스터의 부모. loan-service 의 loan_product 는
 * loan_db 에서 product_id 값으로 본 테이블을 참조한다(FK 없음, cross-DB).
 * common datasource 전용 EMF(commonEntityManagerFactory)에 바인딩된다.
 */
@Getter
@Entity
@Table(name = "common_product")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CommonProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "product_cd", length = 30, unique = true)
    private String productCd;

    @Column(name = "biz_div_cd", length = 10)
    private String bizDivCd;

    @Column(name = "product_name", length = 200)
    private String productName;

    @Column(name = "product_type_cd", length = 20)
    private String productTypeCd;

    @Column(name = "product_description")
    private String productDescription;

    @Column(name = "target_type_cd", length = 50)
    private String targetTypeCd;

    @Column(name = "channel_cd", length = 50)
    private String channelCd;

    @Column(name = "currency_cd", length = 3)
    private String currencyCd;

    @Column(name = "policy_product_yn")
    private Boolean policyProductYn;

    @Column(name = "min_amount")
    private Long minAmount;

    @Column(name = "max_amount")
    private Long maxAmount;

    @Column(name = "min_period_mo")
    private Integer minPeriodMo;

    @Column(name = "max_period_mo")
    private Integer maxPeriodMo;

    @Column(name = "sale_yn")
    private Boolean saleYn;

    @Column(name = "sale_start_date", length = 8)
    private String saleStartDate;

    @Column(name = "sale_end_date", length = 8)
    private String saleEndDate;

    @Column(name = "product_brochure_url", length = 500)
    private String productBrochureUrl;

    @Column(name = "financial_consumer_act_yn")
    private Boolean financialConsumerActYn;

    @Column(name = "product_status", length = 50)
    private String productStatus;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }
}
