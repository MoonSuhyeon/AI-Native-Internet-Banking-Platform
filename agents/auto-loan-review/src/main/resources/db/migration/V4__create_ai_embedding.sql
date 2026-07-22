-- ============================================================
-- V4: RAG 임베딩 스토어 (ai_embedding)
-- 3 코퍼스 공통 테이블 — rag-corpora.md §2 참조
-- ============================================================

-- pgvector: 벡터 유사도 검색 (IVFFlat)
CREATE EXTENSION IF NOT EXISTS vector;

-- pg_trgm: FTS 보조 (한국어 trigram fallback)
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE ai_embedding (
    id              BIGSERIAL       PRIMARY KEY,
    corpus          TEXT            NOT NULL,           -- 'policy_regulation' | 'similar_cases' | 'internal_faq'
    source_id       TEXT            NOT NULL,           -- 코퍼스별 원본 키
    chunk_seq       SMALLINT        NOT NULL DEFAULT 0, -- 한 source 에서 여러 chunk 일 때 순서
    chunk_text      TEXT            NOT NULL,           -- 검색 결과 표시용 원문
    chunk_summary   TEXT,                               -- LLM 입력용 짧은 요약 (선택)
    embedding       vector(1024)    NOT NULL,           -- bge-m3 / text-embedding-005 (1024 차원)
    embedding_model TEXT            NOT NULL DEFAULT 'text-embedding-005',
    metadata        JSONB           NOT NULL DEFAULT '{}'::JSONB,
    fts_tokens      TSVECTOR,                           -- FTS 보조 (pg_trgm + simple config)
    effective_date  DATE,                               -- 정책: 발효일 / 케이스: 결정일 / FAQ: 갱신일
    expiry_date     DATE,                               -- 정책: 폐지일 (그 외 NULL)
    is_active       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_ai_embedding UNIQUE (corpus, source_id, chunk_seq, embedding_model)
);

-- 벡터 유사도 인덱스 (IVFFlat — 100만 chunk 이하, HNSW 는 초과 시 검토)
CREATE INDEX ai_embedding_vec_idx ON ai_embedding
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- 코퍼스 필터 인덱스 (is_active 조건 포함)
CREATE INDEX ai_embedding_corpus_active_idx ON ai_embedding (corpus) WHERE is_active;

-- metadata JSONB 필터 인덱스 (matrix_coord, tags 등 metaFilter 용)
CREATE INDEX ai_embedding_meta_gin_idx ON ai_embedding USING GIN (metadata);

-- FTS 인덱스 (하이브리드 검색 BM25 경로)
CREATE INDEX ai_embedding_fts_gin_idx ON ai_embedding USING GIN (fts_tokens);

-- 정책 유효기간 필터 인덱스
CREATE INDEX ai_embedding_effective_idx ON ai_embedding (corpus, effective_date DESC)
    WHERE is_active;

-- updated_at 자동 갱신 트리거
CREATE OR REPLACE FUNCTION ai_embedding_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ai_embedding_updated_at
    BEFORE UPDATE ON ai_embedding
    FOR EACH ROW EXECUTE FUNCTION ai_embedding_set_updated_at();
