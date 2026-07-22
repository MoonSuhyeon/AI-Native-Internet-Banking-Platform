package com.bank.loan.advisory.domain;

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
 * 과거 심사 사례 인덱스 (append-only). ERD ADVISORY_CASE_INDEX 매핑 (plan §11.3).
 *
 * 종결된 LOAN_REVIEW 1건당 1행. PII 제거된 summary_text + 심사 메타 + 임베딩.
 * embedding 컬럼(VECTOR(1536))은 JPA 엔티티에서 관리하지 않음 (JDBC 전용).
 * 유사도 검색은 SimilarCaseRetriever 의 네이티브 SQL (<=> 연산자)로 수행.
 *
 * Soft Delete 미적용 (append-only). 재인덱싱 시 새 row 추가, 구 row 는 유지.
 */
@Getter
@Entity
@Table(name = "advisory_case_index")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class AdvisoryCaseIndex {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "case_idx_id")
    private Long caseIdxId;

    @Column(name = "rev_id", nullable = false)
    private Long revId;

    @Column(name = "decision_cd", nullable = false, length = 50)
    private String decisionCd;

    @Column(name = "overturn_yn", nullable = false, length = 1)
    private String overturnYn;

    @Column(name = "credit_score")
    private Integer creditScore;

    @Column(name = "credit_score_band_cd", length = 50)
    private String creditScoreBandCd;

    @Column(name = "dsr_ratio_bps")
    private Integer dsrRatioBps;

    @Column(name = "ltv_ratio_bps")
    private Integer ltvRatioBps;

    @Column(name = "cohort_age_band_cd", length = 50)
    private String cohortAgeBandCd;

    @Column(name = "cohort_employment_type_cd", length = 50)
    private String cohortEmploymentTypeCd;

    @Column(name = "cohort_loan_purpose_cd", length = 50)
    private String cohortLoanPurposeCd;

    @Column(name = "summary_text", columnDefinition = "text")
    private String summaryText;

    @Column(name = "embedding_model_cd", nullable = false, length = 50)
    private String embeddingModelCd;

    /**
     * vector(1536) 컬럼 — DDL 생성 전용 매핑.
     * INSERT/UPDATE 는 CaseIndexingService 가 JdbcTemplate + CAST(? AS vector) 로 수행.
     * insertable=false, updatable=false 로 JPA DML 에서 제외.
     */
    @Column(name = "embedding", columnDefinition = "vector(1536)", insertable = false, updatable = false)
    private String embedding;

    @Column(name = "indexed_at", nullable = false)
    private OffsetDateTime indexedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;
}
