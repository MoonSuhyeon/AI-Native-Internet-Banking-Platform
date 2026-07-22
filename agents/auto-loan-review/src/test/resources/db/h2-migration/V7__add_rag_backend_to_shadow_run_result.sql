-- ============================================================
-- V7 (H2): shadow_run_result 에 rag_backend 컬럼 추가 — phase-e-elasticsearch.md E4-2.
-- ============================================================

ALTER TABLE shadow_run_result
    ADD COLUMN rag_backend VARCHAR(16) NOT NULL DEFAULT 'inline';
