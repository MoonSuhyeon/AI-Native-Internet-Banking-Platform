-- ============================================================
-- STAGE 2.5+ RAG 보강 — 정책 인용(C) + 유사 과거 사례 검색(B)
-- Notes:
--   - pgvector 확장 + 4 테이블 + ivfflat cosine 인덱스
--   - Retrieval Only — 생성(G) 미채택 (plan §11.2)
--   - 모든 검색은 advisory_retrieval_log 에 append-only
--   - case_index.summary_text 는 PII 마스킹(정규식) 후 적재
--   - 임베딩 차원 1536 (OpenAI text-embedding-3-small 기준) — 모델 변경 시 V4 마이그레이션
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ------------------------------------------------------------
-- 정책문서 마스터 (등록계)
-- ------------------------------------------------------------
CREATE TABLE advisory_document (
    doc_id                BIGSERIAL    PRIMARY KEY,
    doc_cd                VARCHAR(50)  NOT NULL,
    doc_title             VARCHAR(500) NOT NULL,
    doc_category_cd       VARCHAR(50)  NOT NULL,
    doc_version           VARCHAR(50)  NOT NULL,
    effective_start_date  VARCHAR(8),
    effective_end_date    VARCHAR(8),
    source_uri            VARCHAR(500),
    active_yn             CHAR(1)      NOT NULL DEFAULT 'N',
    doc_desc              VARCHAR(500),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by            BIGINT       NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by            BIGINT       NOT NULL,
    deleted_at            TIMESTAMPTZ,
    deleted_by            BIGINT,
    version               INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_advisory_document_cd_version
    ON advisory_document (doc_cd, doc_version)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_advisory_document_active_effective
    ON advisory_document (active_yn, effective_start_date, effective_end_date)
    WHERE deleted_at IS NULL;

-- ------------------------------------------------------------
-- 정책문서 청크 (append-only) — embedding ivfflat 인덱스
-- ------------------------------------------------------------
CREATE TABLE advisory_document_chunk (
    chunk_id            BIGSERIAL     PRIMARY KEY,
    doc_id              BIGINT        NOT NULL REFERENCES advisory_document(doc_id) ON DELETE NO ACTION,
    chunk_seq           INT           NOT NULL,
    chunk_text          TEXT          NOT NULL,
    section_path        VARCHAR(500),
    chunk_token_count   INT,
    embedding_model_cd  VARCHAR(50)   NOT NULL,
    embedding           VECTOR(1536),
    chunk_meta          JSONB,
    indexed_at          TIMESTAMPTZ   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by          BIGINT        NOT NULL
);
CREATE INDEX idx_advisory_document_chunk_doc
    ON advisory_document_chunk (doc_id, chunk_seq);
CREATE INDEX idx_advisory_document_chunk_model
    ON advisory_document_chunk (embedding_model_cd);
CREATE INDEX idx_advisory_document_chunk_embedding
    ON advisory_document_chunk USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- ------------------------------------------------------------
-- 과거 사례 인덱스 (append-only) — PII 마스킹된 summary + embedding
-- ------------------------------------------------------------
CREATE TABLE advisory_case_index (
    case_idx_id                BIGSERIAL    PRIMARY KEY,
    rev_id                     BIGINT       NOT NULL REFERENCES loan_review(rev_id) ON DELETE NO ACTION,
    decision_cd                VARCHAR(50)  NOT NULL,
    overturn_yn                CHAR(1)      NOT NULL DEFAULT 'N',
    credit_score               INT,
    credit_score_band_cd       VARCHAR(50),
    dsr_ratio_bps              INT,
    ltv_ratio_bps              INT,
    cohort_age_band_cd         VARCHAR(50),
    cohort_employment_type_cd  VARCHAR(50),
    cohort_loan_purpose_cd     VARCHAR(50),
    summary_text               TEXT,
    embedding_model_cd         VARCHAR(50)  NOT NULL,
    embedding                  VECTOR(1536),
    indexed_at                 TIMESTAMPTZ  NOT NULL,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by                 BIGINT       NOT NULL
);
CREATE INDEX idx_advisory_case_index_rev
    ON advisory_case_index (rev_id);
CREATE INDEX idx_advisory_case_index_decision_overturn
    ON advisory_case_index (decision_cd, overturn_yn);
CREATE INDEX idx_advisory_case_index_embedding
    ON advisory_case_index USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- ------------------------------------------------------------
-- 검색 감사 로그 (append-only) — 모든 retrieval 1행 append
-- ------------------------------------------------------------
CREATE TABLE advisory_retrieval_log (
    retr_id                  BIGSERIAL     PRIMARY KEY,
    advr_id                  BIGINT        REFERENCES review_advisory_report(advr_id) ON DELETE NO ACTION,
    retrieval_kind_cd        VARCHAR(50)   NOT NULL,
    rule_cd                  VARCHAR(50),
    query_text               TEXT,
    query_embedding_model_cd VARCHAR(50)   NOT NULL,
    result_count             INT           NOT NULL DEFAULT 0,
    top_score                DECIMAL(10,6),
    result_detail            JSONB,
    requested_by             BIGINT,
    requested_at             TIMESTAMPTZ   NOT NULL,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by               BIGINT        NOT NULL
);
CREATE INDEX idx_advisory_retrieval_log_advr
    ON advisory_retrieval_log (advr_id, requested_at);
CREATE INDEX idx_advisory_retrieval_log_kind
    ON advisory_retrieval_log (retrieval_kind_cd, requested_at);
