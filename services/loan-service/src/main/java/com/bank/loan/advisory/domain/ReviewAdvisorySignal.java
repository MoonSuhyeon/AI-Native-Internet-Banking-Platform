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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 어드바이저리 리포트의 수치적 근거. ERD REVIEW_ADVISORY_SIGNAL 매핑. append-only 이력.
 * 한 리포트(advr_id) 당 다수 signal 가능 — 룰이 여러 지표를 동시에 위반할 때 각 지표별 1행.
 */
@Getter
@Entity
@Table(name = "review_advisory_signal")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReviewAdvisorySignal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "advs_id")
    private Long advsId;

    @Column(name = "advr_id", nullable = false)
    private Long advrId;

    @Column(name = "signal_kind_cd", nullable = false, length = 50)
    private String signalKindCd;

    @Column(name = "signal_metric", nullable = false, length = 100)
    private String signalMetric;

    @Column(name = "observed_value", precision = 20, scale = 6)
    private BigDecimal observedValue;

    @Column(name = "threshold_value", precision = 20, scale = 6)
    private BigDecimal thresholdValue;

    @Column(name = "peer_baseline_value", precision = 20, scale = 6)
    private BigDecimal peerBaselineValue;

    @Column(name = "sample_size")
    private Integer sampleSize;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "signal_detail", columnDefinition = "jsonb")
    private String signalDetail;

    @Column(name = "observed_window_start", length = 8)
    private String observedWindowStart;

    @Column(name = "observed_window_end", length = 8)
    private String observedWindowEnd;

    @Column(name = "observed_at", nullable = false)
    private OffsetDateTime observedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;
}
