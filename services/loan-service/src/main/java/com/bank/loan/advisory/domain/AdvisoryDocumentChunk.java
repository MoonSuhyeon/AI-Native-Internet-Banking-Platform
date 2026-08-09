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

import java.time.OffsetDateTime;

/**
 * 정책문서 청크 (append-only). ERD ADVISORY_DOCUMENT_CHUNK 매핑.
 *
 * embedding 컬럼(VECTOR(1536))은 JPA 엔티티에서 관리하지 않는다.
 * INSERT 는 DocumentIngestionService 가 JdbcTemplate + CAST(? AS vector) 로 수행.
 * 유사도 검색은 PolicyCitationRetriever 의 네이티브 SQL (<=> 연산자)로 수행.
 *
 * Soft Delete 미적용 (append-only — plan §11.3.1).
 */
@Getter
@Entity
@Table(name = "advisory_document_chunk")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class AdvisoryDocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chunk_id")
    private Long chunkId;

    @Column(name = "doc_id", nullable = false)
    private Long docId;

    @Column(name = "chunk_seq", nullable = false)
    private Integer chunkSeq;

    @Column(name = "chunk_text", nullable = false, columnDefinition = "text")
    private String chunkText;

    @Column(name = "section_path", length = 500)
    private String sectionPath;

    @Column(name = "chunk_token_count")
    private Integer chunkTokenCount;

    @Column(name = "embedding_model_cd", nullable = false, length = 50)
    private String embeddingModelCd;

    /**
     * vector(1536) 컬럼 — DDL 생성 전용 매핑.
     * INSERT/UPDATE 는 DocumentIngestionService 가 JdbcTemplate + CAST(? AS vector) 로 수행.
     * insertable=false, updatable=false 로 JPA DML 에서 제외.
     */
    @Column(name = "embedding", columnDefinition = "vector(1536)", insertable = false, updatable = false)
    private String embedding;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "chunk_meta", columnDefinition = "jsonb")
    private String chunkMeta;

    @Column(name = "indexed_at", nullable = false)
    private OffsetDateTime indexedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;
}
