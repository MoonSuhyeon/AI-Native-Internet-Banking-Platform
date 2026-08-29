-- =============================================================
-- V35__grant_loan_ledger_read.sql
-- 여신에 원장 집계 조회 권한을 부여한다
--
-- 보조부(여신)와 원장을 대사하려면 여신이 원장 쪽 값을 읽어야 한다. 한쪽을 다른
-- 쪽에서 뽑아 오면 대사가 항등식이 되므로, 원장은 원장에서만 계산해 넘긴다.
--
-- 자금을 움직이지 않지만 같은 인가 관문을 지난다. 읽기라고 권한을 면제하면
-- "무엇을 할 수 있는가" 의 목록이 반쪽이 되고, 그러면 권한 대장으로서 쓸모가 없다.
--
-- 금액 한도와 계좌 정책은 두지 않는다. 옮기는 자금이 없어 걸 대상이 없다.
-- =============================================================

INSERT INTO service_permission (
    service_id, operation, max_amount, status, granted_by
) VALUES
    ('LOAN_SERVICE', 'LOAN_LEDGER_READ', NULL, 'ACTIVE', 'MIGRATION_V35');

INSERT INTO service_permission_history (
    service_id, operation, action, before_state, after_state, reason, actor
) VALUES
    ('LOAN_SERVICE', 'LOAN_LEDGER_READ', 'GRANT', NULL,
     'max_amount=NULL(자금이동 없음), accounts=[]',
     '보조부↔원장 대사 — C1 ⑤', 'MIGRATION_V35');
