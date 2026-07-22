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

/**
 * 정책·규정·내규 문서 마스터. ERD ADVISORY_DOCUMENT 매핑 (plan §11.3).
 *
 * 같은 문서의 개정은 doc_cd 동일 + doc_version 증가로 새 row.
 * active_yn='Y' 인 문서의 청크만 PolicyCitationRetriever 검색 대상.
 * Soft Delete 적용 (등록계).
 */
@Getter
@Entity
@Table(name = "advisory_document")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class AdvisoryDocument extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "doc_id")
    private Long docId;

    @Column(name = "doc_cd", nullable = false, length = 50)
    private String docCd;

    @Column(name = "doc_title", nullable = false, length = 500)
    private String docTitle;

    @Column(name = "doc_category_cd", nullable = false, length = 50)
    private String docCategoryCd;

    @Column(name = "doc_version", nullable = false, length = 50)
    private String docVersion;

    @Column(name = "effective_start_date", length = 8)
    private String effectiveStartDate;

    @Column(name = "effective_end_date", length = 8)
    private String effectiveEndDate;

    @Column(name = "source_uri", length = 500)
    private String sourceUri;

    @Column(name = "active_yn", nullable = false, length = 1)
    private String activeYn;

    @Column(name = "doc_desc", length = 500)
    private String docDesc;

    public boolean isActive() {
        return "Y".equals(activeYn);
    }

    /** 인입 완료 후 검색 대상으로 활성화. */
    public void activate() {
        this.activeYn = "Y";
    }

    /** 청크 재인입 또는 만료 처리 시 비활성화. */
    public void deactivate() {
        this.activeYn = "N";
    }
}
