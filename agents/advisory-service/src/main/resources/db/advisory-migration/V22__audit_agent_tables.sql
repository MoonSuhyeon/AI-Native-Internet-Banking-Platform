-- =====================================================================
-- V4: 감사/공정성 Agent 테이블
--   - ai_audit_opinion  : LLM 감사 의견 (append-only)
--   - reviewer_risk_score : 심사관별 누적 위험도 스코어
-- =====================================================================

CREATE TABLE ai_audit_opinion (
    opinion_id          BIGSERIAL     PRIMARY KEY,
    advr_id             BIGINT,
    rev_id              BIGINT        NOT NULL,
    reviewer_id         BIGINT,
    analysis_type_cd    VARCHAR(50)   NOT NULL,   -- BIAS_DETECTION | COMPLIANCE_VERIFICATION
    conclusion_cd       VARCHAR(50)   NOT NULL,   -- BIAS_SUSPECTED | NO_BIAS_DETECTED | VIOLATION_SUSPECTED | COMPLIANT | INSUFFICIENT_DATA
    reasoning_summary   VARCHAR(2000),
    confidence_score    NUMERIC(5,4),
    input_tokens        INTEGER,
    output_tokens       INTEGER,
    generated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ai_audit_opinion_advr     ON ai_audit_opinion (advr_id);
CREATE INDEX idx_ai_audit_opinion_reviewer ON ai_audit_opinion (reviewer_id, generated_at DESC);
CREATE INDEX idx_ai_audit_opinion_type     ON ai_audit_opinion (analysis_type_cd, conclusion_cd);

-- =====================================================================

CREATE TABLE reviewer_risk_score (
    score_id            BIGSERIAL     PRIMARY KEY,
    reviewer_id         BIGINT        NOT NULL UNIQUE,
    bias_score          NUMERIC(5,2)  NOT NULL DEFAULT 0,   -- 0~100
    compliance_score    NUMERIC(5,2)  NOT NULL DEFAULT 0,   -- 0~100
    evaluation_count    INTEGER       NOT NULL DEFAULT 0,
    last_evaluated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_reviewer_risk_score_bias       ON reviewer_risk_score (bias_score DESC);
CREATE INDEX idx_reviewer_risk_score_compliance ON reviewer_risk_score (compliance_score DESC);
