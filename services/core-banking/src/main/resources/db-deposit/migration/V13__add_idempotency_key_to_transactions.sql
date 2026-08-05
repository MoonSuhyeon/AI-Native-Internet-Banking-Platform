-- V13: deposit_transactions 테이블에 idempotency_key 컬럼 추가
-- 클라이언트가 동일 키로 재시도해도 이체가 중복 실행되지 않도록 보장한다.
-- 기존 행은 NULL로 채워지며(nullable), 신규 이체 요청부터 키를 강제한다.

ALTER TABLE deposit_transactions
    ADD COLUMN idempotency_key VARCHAR(64) NULL;

CREATE UNIQUE INDEX uq_deposit_transactions_idempotency_key
    ON deposit_transactions (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
