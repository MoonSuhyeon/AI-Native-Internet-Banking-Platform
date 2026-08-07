package com.bank.commonaccount.contract.domain;

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
 * 공통 계약 (common_db.common_contract).
 *
 * 수신/여신 계약이 공유하는 계약 마스터의 부모. loan-service 의 loan_contract 는
 * loan_db 에서 contract_id 값으로 본 테이블을 참조한다(FK 없음, cross-DB).
 * customer_id 는 customer-service 소유 — 값 참조만(FK 없음).
 * common datasource 전용 EMF(commonEntityManagerFactory)에 바인딩된다.
 */
@Getter
@Entity
@Table(name = "common_contract")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CommonContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "contract_id")
    private Long contractId;

    @Column(name = "contract_no", length = 50)
    private String contractNo;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "customer_no", length = 30)
    private String customerNo;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "biz_div_cd", length = 10)
    private String bizDivCd;

    @Column(name = "contract_amount")
    private Long contractAmount;

    @Column(name = "rate_type_cd", length = 10)
    private String rateTypeCd;

    @Column(name = "base_rate_bps")
    private Integer baseRateBps;

    @Column(name = "spread_bps")
    private Integer spreadBps;

    @Column(name = "preferential_bps")
    private Integer preferentialBps;

    @Column(name = "total_rate_bps")
    private Integer totalRateBps;

    @Column(name = "interest_amount_at_maturity")
    private Long interestAmountAtMaturity;

    @Column(name = "contract_start_date", length = 8)
    private String contractStartDate;

    @Column(name = "contract_end_date", length = 8)
    private String contractEndDate;

    @Column(name = "contract_cancel_date", length = 8)
    private String contractCancelDate;

    @Column(name = "contract_cancel_reason", length = 200)
    private String contractCancelReason;

    @Column(name = "auto_transfer_yn")
    private Boolean autoTransferYn;

    @Column(name = "auto_transfer_day")
    private Integer autoTransferDay;

    @Column(name = "signed_at")
    private OffsetDateTime signedAt;

    @Column(name = "contract_channel_cd", length = 20)
    private String contractChannelCd;

    @Column(name = "spot_id")
    private Long spotId;

    @Column(name = "spot_name", length = 100)
    private String spotName;

    @Column(name = "manager_id")
    private Long managerId;

    @Column(name = "manager_name", length = 100)
    private String managerName;

    @Column(name = "proxy_yn")
    private Boolean proxyYn;

    @Column(name = "contract_status", length = 20)
    private String contractStatus;

    @Column(name = "term_url", length = 500)
    private String termUrl;

    @Column(name = "term_hash", length = 64)
    private String termHash;

    @Column(name = "contract_url", length = 500)
    private String contractUrl;

    @Column(name = "contract_hash", length = 64)
    private String contractHash;

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
