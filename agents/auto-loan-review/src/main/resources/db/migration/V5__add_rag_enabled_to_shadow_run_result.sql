-- ============================================================
-- V5: shadow_run_result 에 rag_enabled 컬럼 추가 — phase-d-rag.md D4-2.
-- shadow run 이 RAG 컨텍스트를 사용했는지 여부 기록.
-- ============================================================

ALTER TABLE shadow_run_result
    ADD COLUMN rag_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_srr_rag_enabled ON shadow_run_result(rag_enabled);
