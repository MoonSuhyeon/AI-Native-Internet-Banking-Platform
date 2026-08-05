-- =============================================================================
-- 에이전트 감사 로그 (INSERT-ONLY)
--
-- 이 스크립트는 harness-core 를 쓰는 에이전트가 자기 DB 에 적용한다.
-- 각자 마이그레이션 디렉터리로 복사하고 자기 번호 체계에 맞춰 이름을 바꾼다.
--
-- 왜 에이전트마다 따로 두는가:
--   에이전트가 각자 DB 를 갖고 있어 지금은 중앙 감사 저장소가 없다. 스키마를 통일해
--   먼저 형식을 맞추고, 중앙 수집(Kafka → 단일 저장소)은 별도 과제로 둔다.
--   형식이 같아야 나중에 모을 수 있다.
--
-- 왜 도메인 컬럼(rev_id 등)이 없는가:
--   subject_type + subject_id 문자열 쌍으로 받는다. 도메인 컬럼을 두면
--   그 도메인 밖의 에이전트가 쓸 수 없고, 실제로 그래서 감사 로그가
--   대출심사에만 있었다.
-- =============================================================================

CREATE TABLE IF NOT EXISTS harness_audit_log (
    id                  BIGSERIAL       PRIMARY KEY,
    agent_name          VARCHAR(64)     NOT NULL,
    subject_type        VARCHAR(32)     NOT NULL,
    subject_id          VARCHAR(64)     NOT NULL,

    -- 같은 실행의 추적 식별자. 감사와 추적을 잇는 유일한 연결고리다.
    trace_id            VARCHAR(64),

    request_json        JSONB           NOT NULL DEFAULT '{}',
    output_json         JSONB           NOT NULL DEFAULT '{}',
    tool_calls_json     JSONB           NOT NULL DEFAULT '[]',

    -- LLM 원문. 추적에는 4,000자로 잘려 실리므로 전문은 여기에만 남는다.
    raw_llm_response    TEXT,

    pii_masked          BOOLEAN         NOT NULL DEFAULT TRUE,
    fallback_reason     VARCHAR(64),
    recorded_at         TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_harness_audit_subject
    ON harness_audit_log (subject_type, subject_id, recorded_at DESC);

-- 추적에서 감사로 되짚어 가기 위한 인덱스
CREATE INDEX IF NOT EXISTS ix_harness_audit_trace
    ON harness_audit_log (trace_id);

-- -----------------------------------------------------------------------------
-- INSERT-ONLY 강제
--
-- 애플리케이션에 UPDATE/DELETE 경로를 두지 않는 것만으로는 부족하다.
-- DB 콘솔에서 고칠 수 있으면 감사로 성립하지 않는다.
-- AuditImmutabilityVerifier 가 기동 시 이 트리거의 존재를 확인한다.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION harness_audit_reject_change() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'harness_audit_log 는 추가만 가능합니다 (시도: %)', TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_harness_audit_no_update ON harness_audit_log;
CREATE TRIGGER trg_harness_audit_no_update
    BEFORE UPDATE ON harness_audit_log
    FOR EACH ROW EXECUTE FUNCTION harness_audit_reject_change();

DROP TRIGGER IF EXISTS trg_harness_audit_no_delete ON harness_audit_log;
CREATE TRIGGER trg_harness_audit_no_delete
    BEFORE DELETE ON harness_audit_log
    FOR EACH ROW EXECUTE FUNCTION harness_audit_reject_change();
