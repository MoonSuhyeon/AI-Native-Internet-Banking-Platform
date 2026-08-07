package com.bank.customer.customer.domain;

import com.bank.common.persistence.CreatedOnlyBaseEntity;
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

import java.time.OffsetDateTime;

/**
 * 고객 상태 변경 이력. Append-only 로그 테이블 — soft delete 없음.
 */
@Getter
@Entity
@Table(name = "customer_status_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CustomerStatusHistory extends CreatedOnlyBaseEntity {

    public static final String REASON_JOIN       = "JOIN";
    public static final String REASON_INACTIVITY = "INACTIVITY";
    public static final String REASON_CUST_REQ   = "CUST_REQ";
    public static final String REASON_REACTIVATE = "REACTIVATE";
    public static final String REASON_REGULATORY = "REGULATORY";
    public static final String REASON_OTHER      = "OTHER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "customer_status_history_id")
    private Long customerStatusHistoryId;

    /** 직전 이력 ID. 최초 등록 시 null. */
    @Column(name = "previous_customer_status_history_id")
    private Long previousCustomerStatusHistoryId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** 변경 후 상태 */
    @Column(name = "customer_status_code", nullable = false, length = 20)
    private String customerStatusCode;

    /** 변경 전 상태. 최초 등록 시 null. */
    @Column(name = "previous_customer_status_code", length = 20)
    private String previousCustomerStatusCode;

    @Column(name = "customer_status_change_reason_code", nullable = false, length = 20)
    private String customerStatusChangeReasonCode;

    @Column(name = "customer_status_change_reason_detail", length = 500)
    private String customerStatusChangeReasonDetail;

    @Column(name = "customer_status_effective_start_at", nullable = false)
    private OffsetDateTime customerStatusEffectiveStartAt;

    @Column(name = "customer_status_effective_end_at")
    private OffsetDateTime customerStatusEffectiveEndAt;

    /** 시스템 자동 처리(휴면·만료 등) 여부. T / F */
    @Column(name = "system_auto_triggered_yn", nullable = false)
    private Boolean systemAutoTriggeredYn;

    /** 변경을 수행한 직원 employee_id. 시스템 자동 전환·가입 시 null. */
    @Column(name = "changed_by_employee_id")
    private Long changedByEmployeeId;

    // -------------------------------------------------------------------------
    // 팩토리 메서드
    // -------------------------------------------------------------------------

    /** 신규 가입 시 최초 이력 생성. */
    public static CustomerStatusHistory ofInitial(Long customerId, OffsetDateTime now) {
        return CustomerStatusHistory.builder()
                .customerId(customerId)
                .customerStatusCode(Customer.STATUS_ACTIVE)
                .customerStatusChangeReasonCode(REASON_JOIN)
                .customerStatusEffectiveStartAt(now)
                .systemAutoTriggeredYn(false)
                .build();
    }

    /** 상태 전이 이력 생성. changedByEmployeeId 는 직원 처리 시 X-Employee-Id, 시스템 자동 전환 시 null. */
    public static CustomerStatusHistory ofTransition(
            Long customerId,
            Long previousHistoryId,
            String previousStatusCode,
            String newStatusCode,
            String changeReasonCode,
            String changeReasonDetail,
            OffsetDateTime effectiveStartAt,
            boolean systemTriggered,
            Long changedByEmployeeId) {
        return CustomerStatusHistory.builder()
                .customerId(customerId)
                .previousCustomerStatusHistoryId(previousHistoryId)
                .previousCustomerStatusCode(previousStatusCode)
                .customerStatusCode(newStatusCode)
                .customerStatusChangeReasonCode(changeReasonCode)
                .customerStatusChangeReasonDetail(changeReasonDetail)
                .customerStatusEffectiveStartAt(effectiveStartAt)
                .systemAutoTriggeredYn(systemTriggered)
                .changedByEmployeeId(changedByEmployeeId)
                .build();
    }
}
