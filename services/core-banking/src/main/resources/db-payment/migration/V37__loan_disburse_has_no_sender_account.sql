-- =============================================================
-- V37__loan_disburse_has_no_sender_account.sql
-- 대출 실행의 계좌 정책을 걷어낸다
--
-- V32 는 LOAN_DISBURSE 를 집행계좌(0040000000002)에 묶었다. 그때는 대출 실행이
-- 집행계좌에서 고객계좌로의 이체였으므로 송신계좌를 제한하는 것이 맞았다.
--
-- 그런데 대출 실행을 독립 회계 거래로 분리하면서(V34) 집행계좌가 회계에서 사라졌다.
-- 대출채권이 생기고 고객 예금이 생길 뿐, 어디선가 돈을 빼오지 않는다. 즉 제한할
-- 송신계좌 자체가 없다.
--
-- 정책이 남아 있으면 인가가 ACCOUNT_NOT_ALLOWED 로 거절한다 — 실제로 그렇게 막혔고,
-- service_authorization_log 한 줄이 이유를 바로 알려 주었다. 거절을 남기기로 한
-- 결정이 여기서 값을 했다.
--
-- 남는 통제는 금액 한도다. 도착 계좌는 고객 계좌라 목록으로 묶을 수 없다 — 묶으면
-- 새 고객에게 대출을 실행할 수 없다.
--
-- 상환(LOAN_REPAY)의 계좌 정책은 그대로 둔다. 상환은 여전히 실제 자금이동이고
-- 수납계좌로만 들어와야 한다.
-- =============================================================

DELETE FROM service_permission_account
WHERE permission_id IN (
    SELECT permission_id FROM service_permission
     WHERE service_id = 'LOAN_SERVICE' AND operation = 'LOAN_DISBURSE'
);

INSERT INTO service_permission_history (
    service_id, operation, action, before_state, after_state, reason, actor
) VALUES
    ('LOAN_SERVICE', 'LOAN_DISBURSE', 'MODIFY',
     'max_amount=100000000, accounts=[0040000000002]',
     'max_amount=100000000, accounts=[]',
     '대출 실행에 송신계좌가 없다 — 회계 거래로 분리(V34)되어 집행계좌를 거치지 않는다',
     'MIGRATION_V37');
