-- =============================================================
-- V14__extend_reason_code_reject_code.sql
-- reason_code / reject_code VARCHAR(10) → VARCHAR(20) 확장
--
-- 배경: F4 보상 경로에서 reason_code/reject_code에 "PUBLISH_FAILURE"(15자) 사용.
--       기존 10자 제약으로는 저장 불가 → 20자로 상향. F2/F3 기존값(≤5자) 영향 없음.
--
-- 영향 컬럼:
--   status_history.reason_code          VARCHAR(10) → VARCHAR(20)
--   kftc_clearing_transaction.reject_code VARCHAR(10) → VARCHAR(20)
-- =============================================================

ALTER TABLE status_history
    ALTER COLUMN reason_code TYPE VARCHAR(20);

ALTER TABLE kftc_clearing_transaction
    ALTER COLUMN reject_code TYPE VARCHAR(20);
