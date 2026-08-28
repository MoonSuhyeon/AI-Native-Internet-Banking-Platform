-- V25: 멱등키 유니크 범위를 (키, 계좌)로 좁힌다.
--
-- V13 은 idempotency_key 하나에만 유니크를 걸었다. 그런데 조회는 처음부터
-- (idempotency_key, account_id) 로 하고 있었고, 이체와 결제는 출금·입금 두 다리가
-- 같은 키를 쓴다. 전역 유니크에서는 두 번째 다리를 저장할 수 없어, 키를 저장하는
-- 경로 자체가 성립하지 않았다.
--
-- 조회 방식(findByIdempotencyKeyAndAccountId)과 제약을 같은 모양으로 맞춘다.

DROP INDEX IF EXISTS uq_deposit_transactions_idempotency_key;

CREATE UNIQUE INDEX uq_deposit_transactions_idempotency_key
    ON deposit_transactions (idempotency_key, account_id)
    WHERE idempotency_key IS NOT NULL;
