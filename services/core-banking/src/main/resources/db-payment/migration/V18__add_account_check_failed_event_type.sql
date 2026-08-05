-- =============================================================
-- V18__add_account_check_failed_event_type.sql
-- step2 외부검증 실패 경로 정합:
--   수신계좌 비활성(FROZEN/DORMANT→ACCOUNT_RESTRICTED, CLOSED→ACCOUNT_CLOSED)·
--   사고신고(ACCOUNT_RESTRICTED) 케이스에서 status_history에 기록할
--   event_type ACCOUNT_CHECK_FAILED 추가.
-- V17 기존 45개 전부 포함 (상위집합, 기존 데이터 안전)
--
-- 추가 값:
--   ACCOUNT_CHECK_FAILED : 수신계좌 비활성(FROZEN/DORMANT/CLOSED) 또는
--                          사고신고(fraudFlag=true) 검증 실패 이벤트
-- =============================================================

ALTER TABLE status_history
    DROP CONSTRAINT chk_status_history_event_type;

ALTER TABLE status_history
    ADD CONSTRAINT chk_status_history_event_type
        CHECK (event_type IN (
            -- ── V17 기존 45개 (누락 없이 원문 그대로) ─────────────────────
            'INSTRUCTION_CREATED',
            'OWNER_INQUIRY_DONE', 'OWNER_INQUIRY_FAILED',
            'AUTH_PASSED', 'AUTH_FAILED',
            'SCHEDULED_REGISTERED', 'SCHEDULED_TRIGGERED', 'SCHEDULED_CANCELED',
            'PROCESSING_STARTED',
            'BALANCE_CHECK_FAILED', 'LIMIT_CHECK_FAILED',
            'KFTC_REQUEST_SENT', 'KFTC_ACK_RECEIVED', 'KFTC_REJECT_RECEIVED', 'KFTC_SETTLED',
            'BOK_REQUEST_SENT', 'BOK_ACK_RECEIVED', 'BOK_REJECT_RECEIVED', 'BOK_CONFIRMED',
            'REVERSAL_STARTED', 'REVERSAL_COMPLETED',
            'PAYMENT_COMPLETED', 'PAYMENT_FAILED', 'PAYMENT_CANCELED',
            'INBOUND_REJECTED', 'INBOUND_RECEIVED',
            'SYSTEM_FAILURE_DETECTED',
            'COMPENSATION_STARTED', 'COMPENSATION_COMPLETED', 'COMPENSATION_FAILED',
            'KFTC_ACK_SENT', 'BOK_ACK_SENT',
            'KFTC_SETTLEMENT_SENT', 'BOK_CONFIRM_SENT',
            'KFTC_REJECT_SENT', 'BOK_REJECT_SENT',
            'INBOUND_VALIDATION_PASSED', 'INBOUND_VALIDATION_FAILED',
            'KFTC_REQUEST_FAILED',
            'KFTC_TIMEOUT_DETECTED',
            'KFTC_SETTLEMENT_FAILED',
            'OPERATOR_CANCEL_DECIDED',
            'BOK_REQUEST_FAILED', 'BOK_TIMEOUT_DETECTED', 'BOK_SETTLEMENT_FAILED',
            -- ── V18 신규 1개 ────────────────────────────────────────────────
            'ACCOUNT_CHECK_FAILED'
        ));
