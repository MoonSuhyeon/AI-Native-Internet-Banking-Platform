-- ============================================================
-- V2: Shadow Mode 비교 결과 테이블
-- 에이전트 의사결정 shadow run 결과 보관 — phase-b-operational.md §B3.
-- ai.shadow.enabled=false(기본) 시에도 테이블은 생성됨.
-- ============================================================

CREATE TABLE shadow_run_result (
    id                    BIGSERIAL       PRIMARY KEY,
    rev_id                BIGINT          NOT NULL,
    prod_opinion_json     JSONB           NOT NULL,
    shadow_opinion_json   JSONB           NOT NULL,
    diverged              BOOLEAN         NOT NULL DEFAULT FALSE,
    diverge_reasons       TEXT            NOT NULL DEFAULT '[]',   -- JSON array string
    prod_track            VARCHAR(16)     NOT NULL,
    shadow_track          VARCHAR(16)     NOT NULL,
    prod_decision_score   NUMERIC(6,4),
    shadow_decision_score NUMERIC(6,4),
    shadow_model          VARCHAR(64)     NOT NULL,
    shadow_prompt_version VARCHAR(32)     NOT NULL,
    created_at            TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT chk_prod_track   CHECK (prod_track   IN ('TRACK_1','TRACK_2','TRACK_3')),
    CONSTRAINT chk_shadow_track CHECK (shadow_track IN ('TRACK_1','TRACK_2','TRACK_3'))
);

CREATE INDEX idx_srr_rev_id     ON shadow_run_result(rev_id);
CREATE INDEX idx_srr_created_at ON shadow_run_result(created_at DESC);
CREATE INDEX idx_srr_diverged   ON shadow_run_result(diverged) WHERE diverged = TRUE;
