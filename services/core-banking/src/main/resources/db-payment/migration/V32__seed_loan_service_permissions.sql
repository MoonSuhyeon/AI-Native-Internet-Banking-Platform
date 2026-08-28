-- =============================================================
-- V32__seed_loan_service_permissions.sql
-- LOAN_SERVICE 초기 신원과 작업 권한
--
-- 근거: docs/decisions/transaction-initiator-auth-model.md
--
-- 어휘를 여기서 확정한다. operation 이름은 이후 Deposit·Customer·Agent 의 내부
-- 호출에도 같은 패턴으로 간다. LOAN_EXECUTE·LOAN_CANCEL 처럼 뜻이 겹치는 이름을
-- 나중에 덧붙이면 권한 대장을 한눈에 볼 수 없게 된다.
--
-- 최소 권한(원칙 4)을 값으로 옮긴 결과:
--   · 한도는 유한값이다. NULL(무제한)은 스키마상 가능하지만 쓰지 않는다.
--   · 계좌는 여신 시스템 계좌로 묶는다. 작업 권한만 있고 계좌 제한이 없으면
--     권한을 가진 서비스가 아무 고객 계좌에서나 자금을 뺄 수 있다.
--   · LOAN_REVERSE 는 부여하지 않는다 — 아래 참조.
-- =============================================================


-- ── 신원 ──────────────────────────────────────────────────────────────────────
--
-- 개발용 자격증명의 해시다. 원문은 'dev-secret-loan-service-credential' 이고
-- LOAN_SERVICE_CREDENTIAL 환경변수로 주입한다.
--
-- 원문에 'dev-secret' 을 넣은 것은 의도다. common.security.DevSecretGuard 가
-- 운영 프로파일에서 이 표식을 보고 기동을 거부한다 — 기존 'local-internal-token'
-- 은 어떤 표식도 없어 운영에서 그대로 떠도 아무도 모른다.
--
-- 운영은 자기 자격증명을 넣고 이 행의 해시를 갱신해야 한다. 그 갱신도
-- service_permission_history 에 남긴다.

INSERT INTO service_principal (
    service_id, display_name, credential_hash, status, created_by, updated_by
) VALUES (
    'LOAN_SERVICE',
    '여신 서비스',
    '08170351da0ae699ebb90d5e3515874953c3c4800aeb05dccb1ae71c92eff66e',
    'ACTIVE',
    'MIGRATION_V32',
    'MIGRATION_V32'
);


-- ── 작업 권한 ────────────────────────────────────────────────────────────────
--
-- LOAN_DISBURSE          1억  집행계좌 0040000000002 에서 나간다
-- LOAN_REPAY_PRINCIPAL  1천만 수납계좌 0040000000001 로 들어온다
-- LOAN_REPAY_INTEREST   1천만 수납계좌 0040000000001 로 들어온다

INSERT INTO service_permission (
    service_id, operation, max_amount, status, granted_by
) VALUES
    ('LOAN_SERVICE', 'LOAN_DISBURSE',        100000000, 'ACTIVE', 'MIGRATION_V32'),
    ('LOAN_SERVICE', 'LOAN_REPAY_PRINCIPAL',  10000000, 'ACTIVE', 'MIGRATION_V32'),
    ('LOAN_SERVICE', 'LOAN_REPAY_INTEREST',   10000000, 'ACTIVE', 'MIGRATION_V32');


-- ── 계좌 정책 ────────────────────────────────────────────────────────────────
--
-- permission_id 는 BIGSERIAL 이라 값을 가정하지 않고 조회해서 넣는다.
-- 재실행·번호 어긋남에 영향받지 않는다.

INSERT INTO service_permission_account (permission_id, account_no, created_by)
SELECT p.permission_id, '0040000000002', 'MIGRATION_V32'
FROM service_permission p
WHERE p.service_id = 'LOAN_SERVICE' AND p.operation = 'LOAN_DISBURSE';

INSERT INTO service_permission_account (permission_id, account_no, created_by)
SELECT p.permission_id, '0040000000001', 'MIGRATION_V32'
FROM service_permission p
WHERE p.service_id = 'LOAN_SERVICE'
  AND p.operation IN ('LOAN_REPAY_PRINCIPAL', 'LOAN_REPAY_INTEREST');


-- ── 부여 감사 [1] ────────────────────────────────────────────────────────────
--
-- 권한이 DB 정본이라는 말은 "누가 언제 이 권한을 줬는가" 가 남는다는 뜻이다.
-- 시드로 심은 것도 부여이므로 똑같이 남긴다 — 여기서 빠지면 대장의 첫 줄이
-- 출처 없이 존재하게 된다.

INSERT INTO service_permission_history (
    service_id, operation, action, before_state, after_state, reason, actor
) VALUES
    ('LOAN_SERVICE', 'LOAN_DISBURSE',        'GRANT', NULL,
     'max_amount=100000000, accounts=[0040000000002]',
     '여신 자금이동 경로 복구 — C1 ②', 'MIGRATION_V32'),
    ('LOAN_SERVICE', 'LOAN_REPAY_PRINCIPAL', 'GRANT', NULL,
     'max_amount=10000000, accounts=[0040000000001]',
     '여신 자금이동 경로 복구 — C1 ②', 'MIGRATION_V32'),
    ('LOAN_SERVICE', 'LOAN_REPAY_INTEREST',  'GRANT', NULL,
     'max_amount=10000000, accounts=[0040000000001]',
     '여신 자금이동 경로 복구 — C1 ②', 'MIGRATION_V32');


-- ── LOAN_REVERSE 는 부여하지 않는다 ──────────────────────────────────────────
--
-- 한 서비스 신원이 자금을 내보내고 그 흔적을 되돌리는 일을 모두 할 수 있으면
-- 업무분리(SoD)에 어긋난다. 역분개가 필요해지면 사유와 함께 명시적으로 부여하고,
-- 그 부여가 service_permission_history 에 남는다.
--
-- 지금 이 상태에서 역분개를 시도하면 NO_PERMISSION 으로 거절되고
-- service_authorization_log 에 남는다. 그것이 의도한 동작이다.
