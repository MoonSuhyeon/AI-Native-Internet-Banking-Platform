package com.bank.loan.advisory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 심사관 결정 분포 스냅샷. ERD REVIEWER_DECISION_SNAPSHOT 매핑. append-only.
 * 일/주 단위 코호트(연령대·직업유형·대출목적·지역)별 (승인/거절/보류) 집계 결과를 저장하며
 * 편향 감지(BIAS_*) 룰의 입력 데이터로 사용된다.
 *
 * 자연키: (reviewer_id, snapshot_date, aggregation_window_cd, cohort_dimension_cd, cohort_value)
 */
@Getter
@Entity
@Table(name = "reviewer_decision_snapshot")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReviewerDecisionSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "rds_id")
    private Long rdsId;

    @Column(name = "reviewer_id", nullable = false)
    private Long reviewerId;

    @Column(name = "snapshot_date", nullable = false, length = 8)
    private String snapshotDate;

    @Column(name = "aggregation_window_cd", nullable = false, length = 50)
    private String aggregationWindowCd;

    @Column(name = "cohort_dimension_cd", nullable = false, length = 50)
    private String cohortDimensionCd;

    @Column(name = "cohort_value", nullable = false, length = 100)
    private String cohortValue;

    @Column(name = "total_review_count", nullable = false)
    private Integer totalReviewCount;

    @Column(name = "approve_count", nullable = false)
    private Integer approveCount;

    @Column(name = "reject_count", nullable = false)
    private Integer rejectCount;

    @Column(name = "pending_count", nullable = false)
    private Integer pendingCount;

    @Column(name = "approve_rate_bps", nullable = false)
    private Integer approveRateBps;

    @Column(name = "reject_rate_bps", nullable = false)
    private Integer rejectRateBps;

    @Column(name = "peer_avg_reject_rate_bps")
    private Integer peerAvgRejectRateBps;

    @Column(name = "deviation_sigma", precision = 10, scale = 4)
    private BigDecimal deviationSigma;

    @Column(name = "snapshotted_at", nullable = false)
    private OffsetDateTime snapshottedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;
}
