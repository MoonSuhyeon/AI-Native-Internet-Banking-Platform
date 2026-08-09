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

import java.time.OffsetDateTime;

/**
 * 어드바이저리 리포트 본체. ERD REVIEW_ADVISORY_REPORT 매핑.
 * 한 심사(rev_id) 당 다수 리포트 가능. severity 가 CRITICAL 이면 ack 전 후속 단계 진행 차단.
 *
 * advr_status_cd 라이프사이클:
 *   OPEN → VIEWED → ACKED → RESOLVED
 *              ↓ (AI SUSPECTED 결론)
 *           QUARANTINE  (책임자 재심사 대기)
 *
 * advr_payload(JSONB) 에는 리포트 본문, Phase 6 도입 시 citations[] 등이 격납된다.
 */
@Getter
@Entity
@Table(name = "review_advisory_report")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class ReviewAdvisoryReport extends BaseEntity {

    public static final String STATUS_OPEN       = "OPEN";
    public static final String STATUS_VIEWED     = "VIEWED";
    public static final String STATUS_ACKED      = "ACKED";
    public static final String STATUS_RESOLVED   = "RESOLVED";
    public static final String STATUS_QUARANTINE = "QUARANTINE";

    public static final String SEVERITY_INFO     = "INFO";
    public static final String SEVERITY_WARN     = "WARN";
    public static final String SEVERITY_CRITICAL = "CRITICAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "advr_id")
    private Long advrId;

    @Column(name = "rev_id", nullable = false)
    private Long revId;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "advisory_type_cd", nullable = false, length = 50)
    private String advisoryTypeCd;

    @Column(name = "severity_cd", nullable = false, length = 50)
    private String severityCd;

    @Column(name = "advr_status_cd", nullable = false, length = 50)
    private String advrStatusCd;

    @Column(name = "advr_title", nullable = false, length = 200)
    private String advrTitle;

    @Column(name = "advr_summary", columnDefinition = "text")
    private String advrSummary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "advr_payload", columnDefinition = "jsonb")
    private String advrPayload;

    @Column(name = "target_reviewer_id")
    private Long targetReviewerId;

    @Column(name = "generated_at", nullable = false)
    private OffsetDateTime generatedAt;

    @Column(name = "first_viewed_at")
    private OffsetDateTime firstViewedAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "quarantined_at")
    private OffsetDateTime quarantinedAt;

    public boolean isCritical() {
        return SEVERITY_CRITICAL.equals(severityCd);
    }

    public boolean isUnresolved() {
        return STATUS_OPEN.equals(advrStatusCd) || STATUS_VIEWED.equals(advrStatusCd);
    }

    public void markViewed(OffsetDateTime viewedAt) {
        if (STATUS_OPEN.equals(advrStatusCd)) {
            this.advrStatusCd = STATUS_VIEWED;
            if (this.firstViewedAt == null) {
                this.firstViewedAt = viewedAt;
            }
        }
    }

    public void markAcked() {
        this.advrStatusCd = STATUS_ACKED;
    }

    public void markResolved(OffsetDateTime resolvedAt) {
        this.advrStatusCd = STATUS_RESOLVED;
        this.resolvedAt = resolvedAt;
    }

    public void updatePayload(String advrPayload) {
        this.advrPayload = advrPayload;
    }

    public void markQuarantined(OffsetDateTime at) {
        this.advrStatusCd = STATUS_QUARANTINE;
        this.quarantinedAt = at;
    }
}
