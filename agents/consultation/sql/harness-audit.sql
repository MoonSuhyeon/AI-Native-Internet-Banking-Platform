-- =============================================================================
-- 에이전트 감사 로그 (INSERT-ONLY)
--
-- harness-core 의 db/harness/V001__harness_audit_log.sql 을 consultation DB 에 적용한 것이다.
-- 원본을 고치면 이 파일도 함께 갱신해야 한다.
-- 어긋나면 harness-core/python/tests/test_audit_contract.py 가 먼저 알린다.
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
    recorded_at         TIMESTAMPTZ     NOT NULL DEFAULT now(),

    -- ── 아래 셋은 5단계(fraud)에서 드러난 구멍을 메운 것이다 ──────────────
    --
    -- decision_kind: 한 대상에 판단이 여러 번 있을 수 있다. 사기조사는 사건 하나에
    --   권고(RECOMMENDATION)와 승인 후 실행(ACTION_EXECUTION)이 따로 남는다. 둘 사이에
    --   사람이 있어 거부될 수 있으므로 합칠 수 없다. 구분자가 없으면 "이 사건의 권고를
    --   다오"를 물을 수 없고 최신 1건만 나온다.
    decision_kind       VARCHAR(32)     NOT NULL DEFAULT 'DECISION',

    -- actor_id / actor_roles: 사람 승인을 받는 에이전트에서 "누가 승인했는가"는
    --   부수 정보가 아니라 감사의 핵심이다. 지급정지·STR 을 걸 수 있는 사기조사에서
    --   특히 그렇다. 도메인 지식이 아니라 HITL 이 있는 곳이면 어디나 있는 값이라
    --   payload 가 아니라 컬럼으로 둔다 — JSON 안에 있으면 질의할 수 없고
    --   에이전트마다 이름이 달라진다.
    -- 자율 판단(사람 개입 없음)이면 NULL 이다. 그것도 사실이라 남긴다.
    actor_id            VARCHAR(64),
    actor_roles         JSONB           NOT NULL DEFAULT '[]'
);

-- 이미 만들어진 테이블에도 적용되게 한다.
-- CREATE TABLE IF NOT EXISTS 는 기존 테이블을 만나면 통째로 건너뛰므로
-- 위 세 컬럼이 추가되지 않는다. 초기화 스크립트를 다시 돌리는 에이전트
-- (consultation initdb, goal-agent init_db.py)가 여기에 해당한다.
ALTER TABLE harness_audit_log
    ADD COLUMN IF NOT EXISTS decision_kind VARCHAR(32) NOT NULL DEFAULT 'DECISION';
ALTER TABLE harness_audit_log
    ADD COLUMN IF NOT EXISTS actor_id      VARCHAR(64);
ALTER TABLE harness_audit_log
    ADD COLUMN IF NOT EXISTS actor_roles   JSONB NOT NULL DEFAULT '[]';

CREATE INDEX IF NOT EXISTS ix_harness_audit_subject
    ON harness_audit_log (subject_type, subject_id, recorded_at DESC);

-- "이 사건의 권고를 다오" — 판단 종류를 좁혀 최신 1건을 찾는 경로.
CREATE INDEX IF NOT EXISTS ix_harness_audit_subject_kind
    ON harness_audit_log (subject_type, subject_id, decision_kind, recorded_at DESC);

-- "이 사람이 무엇을 승인했는가" — 행위자로 되짚는 경로.
-- 이 질의가 가능해지는 것이 actor 를 컬럼으로 올린 이유다.
CREATE INDEX IF NOT EXISTS ix_harness_audit_actor
    ON harness_audit_log (actor_id, recorded_at DESC);

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
