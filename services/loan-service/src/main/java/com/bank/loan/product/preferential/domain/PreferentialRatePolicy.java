package com.bank.loan.product.preferential.domain;

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
 * 우대금리 정책. 상품(loan_product) 자식 테이블.
 * 조건 충족 시 차주 적용금리에서 preferential_rate_bps 만큼 차감된다.
 * max_stack_bps 는 여러 정책 누적 한도.
 */
@Getter
@Entity
@Table(name = "preferential_rate_policy")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PreferentialRatePolicy extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "policy_id")
    private Long policyId;

    @Column(name = "prod_id", nullable = false)
    private Long prodId;

    @Column(name = "policy_name", nullable = false, length = 200)
    private String policyName;

    @Column(name = "condition_cd", nullable = false, length = 50)
    private String conditionCd;

    @Column(name = "preferential_rate_bps", nullable = false)
    private Integer preferentialRateBps;

    @Column(name = "max_stack_bps")
    private Integer maxStackBps;

    @Column(name = "active_yn", nullable = false)
    private Boolean activeYn;

    @Column(name = "effective_start_date", length = 8)
    private String effectiveStartDate;

    @Column(name = "effective_end_date", length = 8)
    private String effectiveEndDate;

    @Column(name = "policy_remark", length = 500)
    private String policyRemark;
}
