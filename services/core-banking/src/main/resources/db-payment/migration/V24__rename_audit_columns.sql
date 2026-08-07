-- =============================================================================
-- 결제계 감사 컬럼 이름을 레포 공통 규약에 맞춘다 (8개 테이블 × 4컬럼)
--
--   first_registered_at → created_at
--   first_registrant_id → created_by
--   last_modified_at    → updated_at
--   last_modifier_id    → updated_by
--
-- **왜.** 뜻이 같은 컬럼을 스키마마다 다른 이름으로 부르고 있었다.
--   deposit  : created_at / updated_at        (40 / 39개)
--   customer : created_at / updated_at        (54개)
--   loan     : created_at / updated_at        (103개)
--   payment  : first_registered_at / last_modified_at (16 / 16개)
--
-- 한 DB(core_banking)를 조회하는 사람이 스키마마다 다른 이름을 외워야 한다. consultation 이
-- 이 DB 를 직접 읽고 있어 그 쿼리에도 전파된다. 다수파이자 세 도메인이 이미 쓰는
-- created_at/updated_at 으로 맞춘다.
--
-- created_by/updated_by 는 외부 도메인의 직원 ID 라 FK 가 없다(기존 주석 유지).
-- 타입도 그대로 VARCHAR(20) 다 — 이름만 바꾼다.
-- =============================================================================

ALTER TABLE payment_instruction
    RENAME COLUMN first_registered_at TO created_at;
ALTER TABLE payment_instruction
    RENAME COLUMN first_registrant_id TO created_by;
ALTER TABLE payment_instruction
    RENAME COLUMN last_modified_at TO updated_at;
ALTER TABLE payment_instruction
    RENAME COLUMN last_modifier_id TO updated_by;

ALTER TABLE idempotency_key
    RENAME COLUMN first_registered_at TO created_at;
ALTER TABLE idempotency_key
    RENAME COLUMN first_registrant_id TO created_by;
ALTER TABLE idempotency_key
    RENAME COLUMN last_modified_at TO updated_at;
ALTER TABLE idempotency_key
    RENAME COLUMN last_modifier_id TO updated_by;

ALTER TABLE ledger
    RENAME COLUMN first_registered_at TO created_at;
ALTER TABLE ledger
    RENAME COLUMN first_registrant_id TO created_by;
ALTER TABLE ledger
    RENAME COLUMN last_modified_at TO updated_at;
ALTER TABLE ledger
    RENAME COLUMN last_modifier_id TO updated_by;

ALTER TABLE external_call
    RENAME COLUMN first_registered_at TO created_at;
ALTER TABLE external_call
    RENAME COLUMN first_registrant_id TO created_by;
ALTER TABLE external_call
    RENAME COLUMN last_modified_at TO updated_at;
ALTER TABLE external_call
    RENAME COLUMN last_modifier_id TO updated_by;

ALTER TABLE outbox_message
    RENAME COLUMN first_registered_at TO created_at;
ALTER TABLE outbox_message
    RENAME COLUMN first_registrant_id TO created_by;
ALTER TABLE outbox_message
    RENAME COLUMN last_modified_at TO updated_at;
ALTER TABLE outbox_message
    RENAME COLUMN last_modifier_id TO updated_by;

ALTER TABLE status_history
    RENAME COLUMN first_registered_at TO created_at;
ALTER TABLE status_history
    RENAME COLUMN first_registrant_id TO created_by;
ALTER TABLE status_history
    RENAME COLUMN last_modified_at TO updated_at;
ALTER TABLE status_history
    RENAME COLUMN last_modifier_id TO updated_by;

ALTER TABLE kftc_clearing_transaction
    RENAME COLUMN first_registered_at TO created_at;
ALTER TABLE kftc_clearing_transaction
    RENAME COLUMN first_registrant_id TO created_by;
ALTER TABLE kftc_clearing_transaction
    RENAME COLUMN last_modified_at TO updated_at;
ALTER TABLE kftc_clearing_transaction
    RENAME COLUMN last_modifier_id TO updated_by;

ALTER TABLE bok_settlement_transaction
    RENAME COLUMN first_registered_at TO created_at;
ALTER TABLE bok_settlement_transaction
    RENAME COLUMN first_registrant_id TO created_by;
ALTER TABLE bok_settlement_transaction
    RENAME COLUMN last_modified_at TO updated_at;
ALTER TABLE bok_settlement_transaction
    RENAME COLUMN last_modifier_id TO updated_by;
