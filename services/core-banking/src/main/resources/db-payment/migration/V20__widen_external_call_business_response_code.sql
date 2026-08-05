-- =============================================================
-- V20__widen_external_call_business_response_code.sql
-- external_call.business_response_code VARCHAR(10) → VARCHAR(50) 확장
--
-- 배경: real deposit 연동 시 ErrorCode.name() 원문(예: INSUFFICIENT_BALANCE 20자,
--       TRANSACTION_NOT_FOUND 21자, INTERNAL_SERVER_ERROR 21자)을 박제하다 PSQLException:
--       value too long for type character varying(10) → step3_withdraw 등 FAIL 경로 INSERT 500.
--       fallback "DEPOSIT_HTTP_<status>"(16자)도 동일 오버플로.
--       CLAUDE.md §5(외부 응답 박제 원문 보존) 원칙 → substring 금지, 컬럼 확장이 정답.
--
-- 폭 선정: VARCHAR(50). 동일 테이블 target_system(50)·call_type(30)과 일관.
--          deposit ErrorCode 최대 21자 + KFTC/BOK 외부 코드 확장 여유 포함.
--
-- 영향 컬럼:
--   external_call.business_response_code  VARCHAR(10) → VARCHAR(50)
-- =============================================================

ALTER TABLE external_call
    ALTER COLUMN business_response_code TYPE VARCHAR(50);

COMMENT ON COLUMN external_call.business_response_code
    IS '비즈니스응답코드 — 외부시스템(deposit ErrorCode.name(), KFTC/BOK 코드 등) 원문 박제. V20: VARCHAR(10)→(50)';
