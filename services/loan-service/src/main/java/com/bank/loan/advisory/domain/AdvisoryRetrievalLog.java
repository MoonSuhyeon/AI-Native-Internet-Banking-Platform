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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * RAG 검색 감사 로그 (append-only). ERD ADVISORY_RETRIEVAL_LOG 매핑 (plan §11.3).
 *
 * 정책 인용(POLICY_CITATION) / 유사 사례(SIMILAR_CASE) 검색 1회당 1행 append.
 * 어느 리포트가, 어떤 쿼리로, 몇 건을, 얼마의 최고 유사도로 검색했는지 기록.
 *
 * Soft Delete 미적용 (append-only 감사용 — plan §11.2).
 */
@Getter
@Entity
@Table(name = "advisory_retrieval_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class AdvisoryRetrievalLog {

    public static final String KIND_SIMILAR_CASE    = "SIMILAR_CASE";
    public static final String KIND_POLICY_CITATION = "POLICY_CITATION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "retr_id")
    private Long retrId;

    /** 검색을 유발한 어드바이저리 리포트 ID (nullable — 내부 트리거 검색 시 null 가능). */
    @Column(name = "advr_id")
    private Long advrId;

    @Column(name = "retrieval_kind_cd", nullable = false, length = 50)
    private String retrievalKindCd;

    @Column(name = "rule_cd", length = 50)
    private String ruleCd;

    @Column(name = "query_text", columnDefinition = "text")
    private String queryText;

    @Column(name = "query_embedding_model_cd", nullable = false, length = 50)
    private String queryEmbeddingModelCd;

    @Column(name = "result_count", nullable = false)
    private Integer resultCount;

    @Column(name = "top_score", precision = 10, scale = 6)
    private BigDecimal topScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_detail", columnDefinition = "jsonb")
    private String resultDetail;

    @Column(name = "requested_by")
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;
}
