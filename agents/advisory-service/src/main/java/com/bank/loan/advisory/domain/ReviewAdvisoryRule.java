package com.bank.loan.advisory.domain;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 어드바이저리 트리거 룰 메타데이터. ERD REVIEW_ADVISORY_RULE 매핑.
 * rule_params(JSONB) 에 임계치/대상 컬럼 등 운영자 조정 가능한 파라미터를 격납한다.
 * 룰 비활성화는 active_yn='N' (Soft Delete 와 무관).
 */
@Getter
@Entity
@Table(name = "review_advisory_rule")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReviewAdvisoryRule extends BaseEntity {

    public static final String ACTIVE_Y = "Y";
    public static final String ACTIVE_N = "N";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rule_id")
    private Long ruleId;

    @Column(name = "rule_cd", nullable = false, length = 50)
    private String ruleCd;

    @Column(name = "rule_name", nullable = false, length = 200)
    private String ruleName;

    @Column(name = "advisory_type_cd", nullable = false, length = 50)
    private String advisoryTypeCd;

    @Column(name = "rule_category_cd", nullable = false, length = 50)
    private String ruleCategoryCd;

    @Column(name = "severity_cd", nullable = false, length = 50)
    private String severityCd;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_params", columnDefinition = "jsonb")
    private String ruleParams;

    @Column(name = "rule_version", nullable = false, length = 50)
    private String ruleVersion;

    @Column(name = "active_yn", nullable = false, length = 1)
    private String activeYn;

    @Column(name = "effective_start_date", length = 8)
    private String effectiveStartDate;

    @Column(name = "effective_end_date", length = 8)
    private String effectiveEndDate;

    @Column(name = "rule_desc", length = 500)
    private String ruleDesc;

    public boolean isActive() {
        return ACTIVE_Y.equals(activeYn);
    }

    public void activate()   { this.activeYn = ACTIVE_Y; }
    public void deactivate() { this.activeYn = ACTIVE_N; }

    public void updateParams(String ruleParams, String ruleVersion) {
        this.ruleParams = ruleParams;
        this.ruleVersion = ruleVersion;
    }

    /**
     * 운영자 PUT 으로 룰 메타데이터 갱신. null 인자는 미변경.
     */
    public void updateMeta(String ruleParams, String ruleVersion,
                           String effectiveStartDate, String effectiveEndDate,
                           String ruleDesc) {
        if (ruleParams != null) this.ruleParams = ruleParams;
        if (ruleVersion != null) this.ruleVersion = ruleVersion;
        if (effectiveStartDate != null) this.effectiveStartDate = effectiveStartDate;
        if (effectiveEndDate != null) this.effectiveEndDate = effectiveEndDate;
        if (ruleDesc != null) this.ruleDesc = ruleDesc;
    }
}
