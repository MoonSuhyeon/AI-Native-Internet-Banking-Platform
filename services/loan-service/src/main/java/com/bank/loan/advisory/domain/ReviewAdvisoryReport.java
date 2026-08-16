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
import java.util.Set;

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

    @Column(name = "quarantine_disposition_cd", length = 50)
    private String quarantineDispositionCd;

    @Column(name = "quarantine_disposed_at")
    private OffsetDateTime quarantineDisposedAt;

    @Column(name = "quarantine_disposed_by")
    private Long quarantineDisposedBy;

    @Column(name = "quarantine_disposition_note", columnDefinition = "text")
    private String quarantineDispositionNote;

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

    // ── 격리 처분 ────────────────────────────────────────────────────────────

    /** 정상 판정 — AI 가 의심했으나 문제가 없었다. 격리를 푼다. */
    public static final String DISPOSITION_RELEASED = "RELEASED";
    /** 재심사 배정 — 판단을 미루지 않고 다시 본다. */
    public static final String DISPOSITION_REVIEW_REASSIGNED = "REVIEW_REASSIGNED";
    /** 감사부 조사 의뢰 — 사람 손을 떠나 정식 조사로 넘긴다. */
    public static final String DISPOSITION_AUDIT_REFERRED = "AUDIT_REFERRED";

    private static final Set<String> DISPOSITIONS = Set.of(
            DISPOSITION_RELEASED, DISPOSITION_REVIEW_REASSIGNED, DISPOSITION_AUDIT_REFERRED);

    public boolean isQuarantined() {
        return STATUS_QUARANTINE.equals(advrStatusCd);
    }

    public boolean isDisposed() {
        return quarantineDispositionCd != null;
    }

    /**
     * 격리를 처분한다. <b>격리 상태에서만, 한 번만</b> 가능하다.
     *
     * <p>규칙을 서비스가 아니라 여기 두는 이유는, 처분 경로가 나중에 하나 더 생겨도
     * (배치·관리자 도구) 같은 규칙을 지나게 하기 위해서다. 서비스에 두면 새 경로가
     * 검사를 건너뛸 수 있고, 그 순간 격리가 조용히 풀린다.
     *
     * <p>행위자({@code disposedBy})는 게이트웨이가 검증한 값이어야 한다. 요청 본문에서
     * 온 값을 넣으면 "누가 풀었는가" 가 자칭이 되어 감사 기록의 신뢰도가 그 수준으로
     * 떨어진다.
     */
    public void dispose(String dispositionCd, Long disposedBy, String note, OffsetDateTime at) {
        if (!isQuarantined()) {
            throw new IllegalStateException(
                    "격리 상태가 아니다: advrStatusCd=" + advrStatusCd);
        }
        if (isDisposed()) {
            throw new IllegalStateException(
                    "이미 처분됐다: " + quarantineDispositionCd + " at " + quarantineDisposedAt);
        }
        if (!DISPOSITIONS.contains(dispositionCd)) {
            throw new IllegalArgumentException("알 수 없는 처분: " + dispositionCd);
        }
        if (disposedBy == null) {
            throw new IllegalArgumentException("행위자가 없다 — 누가 풀었는지 남지 않는다");
        }
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("처분 사유가 없다 — 판단의 근거가 남지 않는다");
        }

        this.quarantineDispositionCd = dispositionCd;
        this.quarantineDisposedBy = disposedBy;
        this.quarantineDispositionNote = note.strip();
        this.quarantineDisposedAt = at;

        // 정상 판정만 격리를 끝낸다. 재심사·조사 의뢰는 아직 열려 있는 사안이라
        // 상태를 RESOLVED 로 바꾸면 "처리됐다" 로 집계돼 놓치게 된다.
        if (DISPOSITION_RELEASED.equals(dispositionCd)) {
            markResolved(at);
        }
    }
}
