-- ============================================================
-- Stage 4 — ivfflat 인덱스 튜닝 (G4)
--
-- lists 결정 공식 (pgvector 공식 권장):
--   rows ≤ 1M  →  lists = max(10, ceil(rows / 1000))
--   rows > 1M  →  lists = ceil(sqrt(rows))
--
-- 현재 예상 규모 (시드 + 백필 기준):
--   advisory_document_chunk : ~500 rows  → lists = 10
--   advisory_case_index     : ~1,000 rows → lists = 10
--
-- probes (검색 시 탐색 클러스터 수):
--   권장 probes = max(1, lists / 10)  ← 정확도↑ 원하면 높임
--   SET ivfflat.probes = 1;           ← 연결 수준 또는 트랜잭션 수준 설정
--
-- 규모 증가 시 재조정 쿼리:
--   SELECT count(*) FROM advisory_document_chunk;
--   SELECT count(*) FROM advisory_case_index;
--   → rows > 5,000 이면 lists = ceil(rows / 1000) 로 REINDEX 수행
-- ============================================================

-- advisory_document_chunk: lists 100 → 10 재생성
DROP INDEX IF EXISTS idx_advisory_document_chunk_embedding;
CREATE INDEX idx_advisory_document_chunk_embedding
    ON advisory_document_chunk
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 10);

-- advisory_case_index: lists 100 → 10 재생성
DROP INDEX IF EXISTS idx_advisory_case_index_embedding;
CREATE INDEX idx_advisory_case_index_embedding
    ON advisory_case_index
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 10);
