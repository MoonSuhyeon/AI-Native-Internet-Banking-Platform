-- ============================================================
-- V6: IVFFlat lists 100 → 10 조정
-- pgvector 권고: lists = max(rows/1000, 10)
-- 시드 데이터 13개 기준 lists=100 은 빈 클러스터 다수 → 검색 품질 저하
-- 데이터 100k 초과 시 REINDEX CONCURRENTLY 또는 HNSW 교체 검토
-- ============================================================

DROP INDEX IF EXISTS ai_embedding_vec_idx;

CREATE INDEX ai_embedding_vec_idx ON ai_embedding
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 10);
