-- V7: external_call.call_type CHECK에 정상 원호출 3종 추가
-- ACCOUNT_INQUIRY(A-1), BALANCE_WITHDRAW(B-3), BALANCE_DEPOSIT(B-4)
-- 기존 _CANCEL 보상호출만 있고 원호출이 누락됐던 spec 버그 수정
-- LIMIT_CONSUME은 제외: deposit API 합의서 가정6(한도차감 일체화 — B-3 출금이
--   잔액+한도 동시 차감)에 따라 별도 호출이 발생하지 않음. 단 가정6은 deposit 팀
--   미확정(🟡)이므로, deposit이 "한도 별도 차감"으로 확정 시 B-3.5 LIMIT_CONSUME
--   부활 + 본 CHECK 재검토 필요.

ALTER TABLE external_call
    DROP CONSTRAINT IF EXISTS chk_external_call_call_type;

ALTER TABLE external_call
    ADD CONSTRAINT chk_external_call_call_type
        CHECK (call_type IN (
            'ACCOUNT_OWNER_INQUIRY', 'BALANCE_INQUIRY', 'LIMIT_CHECK', 'AUTH_VERIFY',
            'FRAUD_CHECK', 'KFTC_GATEWAY', 'BOK_GATEWAY', 'INBOUND_RESPONSE',
            'BALANCE_WITHDRAW_CANCEL', 'BALANCE_DEPOSIT_CANCEL', 'LIMIT_CONSUME_CANCEL', 'AUTH_REVOKE',
            'ACCOUNT_INQUIRY', 'BALANCE_WITHDRAW', 'BALANCE_DEPOSIT'
        ));
