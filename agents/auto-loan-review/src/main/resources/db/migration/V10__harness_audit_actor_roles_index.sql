-- =============================================================================
-- 감사 로그 actor_roles GIN 인덱스
--
-- harness-core 의 db/harness/V001__harness_audit_log.sql 이 확장된 것을 따라간다.
-- **V9 를 고치지 않고 새 버전으로 낸다** — 이미 적용된 마이그레이션을 수정하면
-- Flyway 체크섬이 어긋나 기동이 실패한다. 이 레포에서 실제로 겪은 적이 있다.
--
-- 왜 필요한가 (docs/decisions/agent-harness-consolidation.md 다음 순서 4):
--   "FRAUD_OFFICER 가 승인한 것" 같은 질의는 actor_roles JSONB 배열에
--   @> '["FRAUD_OFFICER"]' 로 묻는다. V9 가 만든 것은 actor_id 의 B-tree 라
--   이 연산자를 받지 못하고, 감사 테이블 전체를 훑는다.
--
-- jsonb_path_ops 를 쓰는 이유: 기본 연산자 클래스보다 인덱스가 작고 @> 가 빠르다.
-- 키 존재(?) 질의는 받지 못하지만, 역할 배열에 그 질의를 쓸 일이 없다.
--
-- 대출심사는 사람 승인이 감사에 안 남는 경로라 actor_roles 가 대부분 '[]' 다.
-- 그래도 인덱스를 함께 두는 것은 스키마를 네 벌 동일하게 유지하기 위해서다 —
-- 형식이 같아야 나중에 한곳에 모을 수 있다.
-- =============================================================================

CREATE INDEX IF NOT EXISTS ix_harness_audit_actor_roles
    ON harness_audit_log USING GIN (actor_roles jsonb_path_ops);
