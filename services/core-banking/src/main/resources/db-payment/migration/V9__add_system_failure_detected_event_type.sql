-- =============================================================
-- V9__add_system_failure_detected_event_type.sql
-- F8(자행 입금실패 보상) 이벤트종류 추가
-- chk_status_history_event_type 에 SYSTEM_FAILURE_DETECTED 추가
-- =============================================================

ALTER TABLE status_history
    DROP CONSTRAINT chk_status_history_event_type;

ALTER TABLE status_history
    ADD CONSTRAINT chk_status_history_event_type
        CHECK (event_type IN (
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
            'INBOUND_VALIDATION_PASSED', 'INBOUND_VALIDATION_FAILED'
        ));
