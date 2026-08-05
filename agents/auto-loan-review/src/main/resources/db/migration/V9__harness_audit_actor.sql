-- =============================================================================
-- 감사 로그에 행위자·판단종류 추가
--
-- harness-core 의 db/harness/V001__harness_audit_log.sql 이 확장된 것을 따라간다.
-- **V8 을 고치지 않고 새 버전으로 낸다** — 이미 적용된 마이그레이션을 수정하면
-- Flyway 체크섬이 어긋나 기동이 실패한다. 이 레포에서 실제로 겪은 적이 있다.
--
-- 왜 추가하는가 (docs/decisions/agent-harness-consolidation.md 5단계):
--   decision_kind — 한 대상에 판단이 여러 번 있을 수 있다. 사기조사는 사건 하나에
--     권고와 승인 후 실행이 따로 남고, 사이에 사람이 있어 합칠 수 없다.
--     구분자가 없으면 "이 대상의 권고를 다오"를 물을 수 없다.
--   actor_id/actor_roles — 사람 승인을 받는 에이전트에서 "누가 승인했는가"는
--     감사의 핵심이다. JSON 안에 있으면 질의할 수 없고 에이전트마다 이름이 달라진다.
--
-- 대출심사는 사람 승인이 감사에 안 남는 경로라 actor 가 NULL 로 들어간다.
-- 그것도 사실이므로 남긴다 — 자율 판단이었다는 뜻이다.
-- =============================================================================

ALTER TABLE harness_audit_log
    ADD COLUMN IF NOT EXISTS decision_kind VARCHAR(32) NOT NULL DEFAULT 'DECISION';

ALTER TABLE harness_audit_log
    ADD COLUMN IF NOT EXISTS actor_id      VARCHAR(64);

ALTER TABLE harness_audit_log
    ADD COLUMN IF NOT EXISTS actor_roles   JSONB NOT NULL DEFAULT '[]';

-- "이 대상의 특정 종류 판단 중 최신" 조회 경로
CREATE INDEX IF NOT EXISTS ix_harness_audit_subject_kind
    ON harness_audit_log (subject_type, subject_id, decision_kind, recorded_at DESC);

-- "이 사람이 무엇을 승인했는가" — 행위자로 되짚는 경로
CREATE INDEX IF NOT EXISTS ix_harness_audit_actor
    ON harness_audit_log (actor_id, recorded_at DESC);
