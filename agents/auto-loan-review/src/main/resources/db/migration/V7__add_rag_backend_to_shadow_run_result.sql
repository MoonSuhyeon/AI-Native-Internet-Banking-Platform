-- ============================================================
-- V7: shadow_run_result 에 rag_backend 컬럼 추가 — phase-e-elasticsearch.md E4-2.
-- shadow run 이 사용한 RAG 백엔드(inline / es)를 기록.
-- ============================================================

ALTER TABLE shadow_run_result
    ADD COLUMN rag_backend VARCHAR(16) NOT NULL DEFAULT 'inline';

CREATE INDEX idx_srr_rag_backend ON shadow_run_result(rag_backend);
