-- =============================================================
-- V33__correct_loan_repay_operation.sql
-- 상환 작업 어휘를 자금이동 단위로 교정한다
--
-- V32 는 상환을 LOAN_REPAY_PRINCIPAL / LOAN_REPAY_INTEREST 두 작업으로 심었다.
-- 그런데 실제 상환은 결제 한 건으로 합산 금액이 나간다 —
-- AutoDebitBatchService 가 schedule.getScheduledTotal()(원금+이자+연체이자)을
-- 하나의 PaymentRequest 로 보낸다. 둘 중 무엇을 선언해도 사실이 아니다.
--
-- 두 축이 섞였던 것이다.
--
--   operation     자금이동 단위 — 돈이 어디서 어디로 얼마나 움직이는가
--   journal_type  회계 분개 단위 — 그 움직임을 어떻게 기록하는가
--
-- 상환 1건은 자금이동 1회이고 원장 분개는 3다리다.
--
--   DEBIT   고객계좌      103,000     ← 자금이동 1건 = operation 1개
--   CREDIT  대출채권      100,000     ← 분개 3다리 = journal_type 3개
--   CREDIT  이자수익        3,000
--
-- 따라서 원금·이자 구분은 operation 이 아니라 journal_type 에 속한다. 그 어휘는
-- 원장을 완결하는 C1 ④ 에서 나온다.
--
-- 권한을 회수하고 다시 부여하는 흐름을 그대로 쓴다. 레지스트리를 WORM 으로 잠그지
-- 않은 이유가 이것이다 — 권한은 회수돼야 하고, 그 변경이 history 에 남는다.
-- =============================================================


-- ── 회수 ─────────────────────────────────────────────────────────────────────

UPDATE service_permission
SET status     = 'REVOKED',
    revoked_at = now(),
    revoked_by = 'MIGRATION_V33'
WHERE service_id = 'LOAN_SERVICE'
  AND operation IN ('LOAN_REPAY_PRINCIPAL', 'LOAN_REPAY_INTEREST');

INSERT INTO service_permission_history (
    service_id, operation, action, before_state, after_state, reason, actor
) VALUES
    ('LOAN_SERVICE', 'LOAN_REPAY_PRINCIPAL', 'REVOKE',
     'max_amount=10000000, accounts=[0040000000001]', NULL,
     '자금이동 단위가 아니다 — 원금·이자 구분은 journal_type 으로 옮긴다', 'MIGRATION_V33'),
    ('LOAN_SERVICE', 'LOAN_REPAY_INTEREST',  'REVOKE',
     'max_amount=10000000, accounts=[0040000000001]', NULL,
     '자금이동 단위가 아니다 — 원금·이자 구분은 journal_type 으로 옮긴다', 'MIGRATION_V33');


-- ── 부여 ─────────────────────────────────────────────────────────────────────
--
-- 한도와 계좌는 회수한 둘과 같다. 합산 금액이 나가지만 회차당 상환액이 1천만을
-- 넘지 않으므로 한도를 올릴 이유가 없다. 넘는 건이 생기면 그때 사유와 함께 올린다.

INSERT INTO service_permission (
    service_id, operation, max_amount, status, granted_by
) VALUES
    ('LOAN_SERVICE', 'LOAN_REPAY', 10000000, 'ACTIVE', 'MIGRATION_V33');

INSERT INTO service_permission_account (permission_id, account_no, created_by)
SELECT p.permission_id, '0040000000001', 'MIGRATION_V33'
FROM service_permission p
WHERE p.service_id = 'LOAN_SERVICE' AND p.operation = 'LOAN_REPAY';

INSERT INTO service_permission_history (
    service_id, operation, action, before_state, after_state, reason, actor
) VALUES
    ('LOAN_SERVICE', 'LOAN_REPAY', 'GRANT', NULL,
     'max_amount=10000000, accounts=[0040000000001]',
     '상환은 결제 한 건으로 합산 금액이 나간다 — 자금이동 단위로 교정', 'MIGRATION_V33');


-- 회수한 두 작업의 계좌 정책 행은 지우지 않는다. 권한이 REVOKED 라 판정에서
-- 걸러지고, 남겨 두면 "그때 어떤 계좌로 무엇이 허용됐는가" 를 되짚을 수 있다.
