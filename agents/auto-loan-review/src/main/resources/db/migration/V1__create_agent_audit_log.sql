-- ============================================================
-- V1: 에이전트 감사 로그 테이블 (불변 INSERT-ONLY)
-- 보존 정책: 5년 (여신전문금융업법 §52의2)
-- ============================================================

CREATE TABLE agent_audit_log (
    id                  BIGSERIAL       PRIMARY KEY,
    rev_id              BIGINT          NOT NULL,
    schema_version      VARCHAR(10)     NOT NULL DEFAULT 'v1',
    track               VARCHAR(16)     NOT NULL,
    request_snapshot    JSONB           NOT NULL,
    opinion_json        JSONB           NOT NULL,
    tool_calls_json     JSONB           NOT NULL DEFAULT '[]',
    raw_llm_response    TEXT,
    pii_masked          BOOLEAN         NOT NULL DEFAULT TRUE,
    fallback_reason     VARCHAR(64),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    retention_until     DATE            NOT NULL
        GENERATED ALWAYS AS (timezone('UTC', created_at)::DATE + '5 years'::INTERVAL) STORED,

    CONSTRAINT chk_aal_schema_version CHECK (schema_version IN ('v1')),
    CONSTRAINT chk_aal_track          CHECK (track IN ('TRACK_1','TRACK_2','TRACK_3')),
    CONSTRAINT chk_aal_opinion_size   CHECK (pg_column_size(opinion_json) < 65536),
    CONSTRAINT chk_aal_request_size   CHECK (pg_column_size(request_snapshot) < 131072)
);

CREATE INDEX idx_aal_rev_id     ON agent_audit_log(rev_id);
CREATE INDEX idx_aal_created_at ON agent_audit_log(created_at DESC);

-- INSERT-ONLY 보장: UPDATE/DELETE 차단 트리거
CREATE OR REPLACE FUNCTION fn_aal_block_mutate()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'agent_audit_log is INSERT-ONLY. UPDATE/DELETE is forbidden (여신전문금융업법 §52의2).';
END;
$$;

CREATE TRIGGER trg_aal_no_update
    BEFORE UPDATE ON agent_audit_log
    FOR EACH ROW EXECUTE FUNCTION fn_aal_block_mutate();

CREATE TRIGGER trg_aal_no_delete
    BEFORE DELETE ON agent_audit_log
    FOR EACH ROW EXECUTE FUNCTION fn_aal_block_mutate();

-- 보존 기간 만료 파티션 분리용 뷰 (실제 삭제는 DBA 승인 프로세스)
CREATE VIEW agent_audit_log_expired AS
    SELECT * FROM agent_audit_log WHERE retention_until < CURRENT_DATE;
