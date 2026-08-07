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
 * 고객 등급 변경 이력. Append-only 로그 테이블 — soft delete 없음.
 */
@Getter
@Entity
@Table(name = "customer_grade_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CustomerGradeHistory extends CreatedOnlyBaseEntity {

    public static final String REASON_JOIN         = "JOIN";
    public static final String REASON_TRANSACTION  = "TRANSACTION";
    public static final String REASON_ADMIN        = "ADMIN";
    public static final String REASON_SYSTEM       = "SYSTEM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "customer_grade_history_id")
    private Long customerGradeHistoryId;

    @Column(name = "previous_customer_grade_history_id")
    private Long previousCustomerGradeHistoryId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "customer_grade_code", nullable = false, length = 10)
    private String customerGradeCode;

    @Column(name = "previous_customer_grade_code", length = 10)
    private String previousCustomerGradeCode;

    @Column(name = "customer_grade_change_reason_code", nullable = false, length = 20)
    private String customerGradeChangeReasonCode;

    @Column(name = "customer_grade_change_reason_detail", length = 500)
    private String customerGradeChangeReasonDetail;

    /** YYYYMMDD */
    @Column(name = "customer_grade_effective_start_date", nullable = false, length = 8)
    private String customerGradeEffectiveStartDate;

    @Column(name = "customer_grade_effective_end_date", length = 8)
    private String customerGradeEffectiveEndDate;

    @Column(name = "customer_grade_evaluated_at", nullable = false)
    private OffsetDateTime customerGradeEvaluatedAt;

    @Column(name = "system_auto_triggered_yn", nullable = false, length = 1)
    private Boolean systemAutoTriggeredYn;

    /** 변경을 수행한 직원 employee_id. 시스템 자동 평가·가입 시 null. */
    @Column(name = "changed_by_employee_id")
    private Long changedByEmployeeId;

    public static CustomerGradeHistory ofInitial(Long customerId, String gradeCode,
                                                  String startDate, OffsetDateTime evaluatedAt) {
        return CustomerGradeHistory.builder()
                .customerId(customerId)
                .customerGradeCode(gradeCode)
                .customerGradeChangeReasonCode(REASON_JOIN)
                .customerGradeEffectiveStartDate(startDate)
                .customerGradeEvaluatedAt(evaluatedAt)
                .systemAutoTriggeredYn(false)
                .build();
    }

    public static CustomerGradeHistory ofTransition(Long customerId, Long previousHistoryId,
                                                     String previousGrade, String newGrade,
                                                     String reasonCode, String reasonDetail,
                                                     String startDate, OffsetDateTime evaluatedAt,
                                                     boolean systemTriggered, Long changedByEmployeeId) {
        return CustomerGradeHistory.builder()
                .customerId(customerId)
                .previousCustomerGradeHistoryId(previousHistoryId)
                .previousCustomerGradeCode(previousGrade)
                .customerGradeCode(newGrade)
                .customerGradeChangeReasonCode(reasonCode)
                .customerGradeChangeReasonDetail(reasonDetail)
                .customerGradeEffectiveStartDate(startDate)
                .customerGradeEvaluatedAt(evaluatedAt)
                .systemAutoTriggeredYn(systemTriggered)
                .changedByEmployeeId(changedByEmployeeId)
                .build();
    }
}
