package com.bank.customer.fds.domain;

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
 * FDS 탐지 룰 (fds_rule 테이블).
 * condition_json 형식 (MVP): {"window_minutes": 30, "threshold": 5}
 * action_type: BLOCK(즉시 차단), CHALLENGE(추가 인증 요구), MONITOR(기록만)
 */
@Entity
@Table(name = "fds_rule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class FdsRule extends BaseEntity {

    public static final String ACTION_BLOCK     = "BLOCK";
    public static final String ACTION_CHALLENGE = "CHALLENGE";
    public static final String ACTION_MONITOR   = "MONITOR";

    public static final String CATEGORY_LOGIN_FAILURE_COUNT  = "LOGIN_FAILURE_COUNT";
    public static final String CATEGORY_CERT_FAILURE_COUNT   = "CERT_FAILURE_COUNT";
    public static final String CATEGORY_PASSWORD_CHANGE_FREQ = "PASSWORD_CHANGE_FREQ";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "fds_rule_id")
    private Long fdsRuleId;

    @Column(name = "fds_rule_code", nullable = false, length = 30)
    private String fdsRuleCode;

    @Column(name = "fds_rule_name", nullable = false, length = 100)
    private String fdsRuleName;

    @Column(name = "fds_rule_category_code", nullable = false, length = 30)
    private String fdsRuleCategoryCode;

    @Column(name = "fds_rule_target_event_code", nullable = false, length = 50)
    private String fdsRuleTargetEventCode;

    /** {"window_minutes": 30, "threshold": 5} */
    @Column(name = "fds_rule_condition_json", nullable = false, columnDefinition = "JSON")
    private String fdsRuleConditionJson;

    @Column(name = "fds_rule_risk_weight", nullable = false)
    private int fdsRuleRiskWeight;

    @Column(name = "fds_rule_action_type_code", nullable = false, length = 20)
    private String fdsRuleActionTypeCode;

    /** 'T' = 활성, 'F' = 비활성 */
    @Column(name = "fds_rule_active_yn", nullable = false, length = 1)
    private Boolean fdsRuleActiveYn;

    @Column(name = "fds_rule_effective_date", nullable = false, length = 8)
    private String fdsRuleEffectiveDate;

    @Column(name = "fds_rule_expiry_date", length = 8)
    private String fdsRuleExpiryDate;

    public boolean isActive() {
        return Boolean.TRUE.equals(fdsRuleActiveYn);
    }

    public void activate()   { this.fdsRuleActiveYn = true; }
    public void deactivate() { this.fdsRuleActiveYn = false; }
    public void updateCondition(String conditionJson) { this.fdsRuleConditionJson = conditionJson; }
    public void updateActionType(String actionType)   { this.fdsRuleActionTypeCode = actionType; }
}
