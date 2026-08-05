package com.bank.loan.document.domain;

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

import java.time.OffsetDateTime;

/**
 * 대출 서류. ERD STAGE 3 LOAN_DOCUMENT 매핑.
 */
@Getter
@Entity
@Table(name = "loan_document")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class LoanDocument extends BaseEntity {

    public static final String STATUS_UPLOADED  = "UPLOADED";
    public static final String STATUS_VERIFIED  = "VERIFIED";
    public static final String STATUS_REJECTED  = "REJECTED";
    public static final String STATUS_DELETED   = "DELETED";

    /** doc-agent 미연결/일시장애로 검증을 수행하지 못한 상태. 추후 재검증 대상. */
    public static final String VERIFY_PENDING   = "PENDING";

    public static final String SOURCE_MOBILE   = "MOBILE";
    public static final String SOURCE_COUNTER  = "COUNTER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "doc_id")
    private Long docId;

    @Column(name = "appl_id", nullable = false)
    private Long applId;

    @Column(name = "doc_type_cd", nullable = false, length = 50)
    private String docTypeCd;

    @Column(name = "doc_status_cd", nullable = false, length = 50)
    private String docStatusCd;

    @Column(name = "doc_source_cd", length = 50)
    private String docSourceCd;

    @Column(name = "doc_name", length = 200)
    private String docName;

    @Column(name = "doc_url", length = 500)
    private String docUrl;

    @Column(name = "doc_hash", length = 128)
    private String docHash;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "verify_result_cd", length = 50)
    private String verifyResultCd;

    @Column(name = "retention_until", length = 8)
    private String retentionUntil;

    /** 원본 파일 파기 시각. null 이면 원본 보관 중. 메타데이터는 파기 후에도 유지된다. */
    @Column(name = "purged_at")
    private OffsetDateTime purgedAt;

    /** 상태 전이만 수행. soft delete (deleted_at/by) 는 BaseEntity.softDelete() 로 별도 호출. */
    public void markDeleted() {
        this.docStatusCd = STATUS_DELETED;
    }

    public void markRetained(String retentionUntil) {
        this.retentionUntil = retentionUntil;
    }

    /**
     * 보존기한이 지나 원본을 파기했음을 기록한다.
     * 서류 row 자체는 남긴다 — 무엇을 언제 파기했는지가 증빙이기 때문이다.
     */
    public void markPurged(OffsetDateTime purgedAt) {
        this.purgedAt = purgedAt;
    }

    /** 원본이 이미 파기됐는가. 파기된 서류는 다운로드할 수 없다. */
    public boolean isPurged() {
        return this.purgedAt != null;
    }

    /**
     * doc-agent 미연결/일시장애로 검증을 보류한다.
     * 문서 상태는 UPLOADED 로 유지하고(추후 재검증 대상) verify_result_cd 만 PENDING 으로 표시한다.
     */
    public void markVerificationDeferred() {
        this.verifyResultCd = VERIFY_PENDING;
    }

    /** doc-agent 검증 결과 반영. submissionId 는 docUrl 에 보존. */
    public void applyVerifyResult(String verifyStatus, String submissionId) {
        this.docUrl        = submissionId;
        this.verifyResultCd = verifyStatus;
        switch (verifyStatus) {
            case "AUTO_PASS" -> {
                this.docStatusCd = STATUS_VERIFIED;
                this.verifiedAt  = OffsetDateTime.now();
            }
            case "NEEDS_RESUBMIT" -> this.docStatusCd = STATUS_REJECTED;
            // HOLD: STATUS_UPLOADED 유지
        }
    }
}
