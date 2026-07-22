-- ============================================================
-- V5 (H2): shadow_run_result 에 rag_enabled 컬럼 추가 — phase-d-rag.md D4-2.
-- ============================================================

ALTER TABLE shadow_run_result
    ADD COLUMN rag_enabled BOOLEAN NOT NULL DEFAULT FALSE;
