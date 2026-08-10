-- =============================================================
-- V30__allow_limit_lookup_failed.sql
-- 실패분류에 LIMIT_LOOKUP_FAILED 추가
--
-- 고객당 인터넷뱅킹 이체한도를 이체 시점에 적용하면서 생긴 새 실패 사유다.
-- 한도 값은 customer-service 가 갖고 있는데, 그 조회가 실패하면 이체를 막는다 —
-- 한도는 고객이 스스로 낮춰 둔 안전장치라, 읽지 못했다는 이유로 무시하면 그 약속을
-- 깨는 것이기 때문이다(fail-closed).
--
-- LIMIT_EXCEEDED 와 구분해서 남긴다. "한도를 넘었다" 와 "한도를 못 읽었다" 는 원인도
-- 조치도 다르다 — 앞은 고객에게 안내할 일이고, 뒤는 우리 쪽 장애다. 한 값으로 묶으면
-- customer-service 장애가 고객 과실처럼 집계된다.
-- =============================================================

ALTER TABLE payment_instruction
    DROP CONSTRAINT chk_payment_instruction_failure_category;

ALTER TABLE payment_instruction
    ADD CONSTRAINT chk_payment_instruction_failure_category
        CHECK (failure_category IS NULL OR failure_category IN (
            'INSUFFICIENT_BALANCE', 'LIMIT_EXCEEDED', 'AUTH_FAILED', 'OWNER_INQUIRY_FAILED',
            'KFTC_REJECTED', 'KFTC_TIMEOUT', 'BOK_REJECTED', 'BOK_TIMEOUT',
            'INVALID_ACCOUNT', 'FRAUD_DETECTED', 'SYSTEM_ERROR',
            'ACCOUNT_RESTRICTED', 'ACCOUNT_NOT_FOUND', 'ACCOUNT_CLOSED',
            'LIMIT_LOOKUP_FAILED'
        ));
