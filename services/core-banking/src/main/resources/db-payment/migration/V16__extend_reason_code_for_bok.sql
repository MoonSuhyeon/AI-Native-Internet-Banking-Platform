-- =============================================================
-- V16__extend_reason_code_for_bok.sql
-- BOK 이벤트 문자열(BOK_SETTLEMENT_FAILED=21자) 수용을 위한 컬럼 확장.
--
-- V14에서 status_history.reason_code / kftc_clearing_transaction.reject_code → VARCHAR(20).
-- bok_settlement_transaction.reject_code는 V11 VARCHAR(10)으로 V14 미포함.
--
-- 영향 컬럼:
--   status_history.reason_code                VARCHAR(20) → VARCHAR(30)  ← BOK_SETTLEMENT_FAILED(21자) 수용
--   bok_settlement_transaction.reject_code    VARCHAR(10) → VARCHAR(30)  ← V14 미포함, 전부 초과
--   kftc_clearing_transaction.reject_code     VARCHAR(20) → VARCHAR(30)  ← 일관성 (KFTC 이벤트 ≤20자라 당장 무관)
-- =============================================================

ALTER TABLE status_history
    ALTER COLUMN reason_code TYPE VARCHAR(30);

ALTER TABLE bok_settlement_transaction
    ALTER COLUMN reject_code TYPE VARCHAR(30);

ALTER TABLE kftc_clearing_transaction
    ALTER COLUMN reject_code TYPE VARCHAR(30);
