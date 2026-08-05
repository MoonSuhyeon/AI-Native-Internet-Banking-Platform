-- =============================================================
-- V13__add_f4_f6_f7_event_types.sql
-- status_history.event_type CHECK에 F4/F6/F7 관련 6개 값 추가
-- 기존 V9 38개 값 전부 포함 (상위집합, 기존 데이터 안전)
--
-- 추가 값:
--   KFTC_REQUEST_FAILED     : F4  Kafka/네트워크 장애로 KFTC 송신 실패
--   KFTC_TIMEOUT_DETECTED   : F6  ACK/정산 미수신 타임아웃 도래 (폴링 워커)
--   KFTC_SETTLEMENT_FAILED  : F7  ACK 이후 정산실패 통보
--   OPERATOR_CANCEL_DECIDED : F6  운영자 강제 취소 결정 (triggered_by=OPERATOR)
--   BOK_TIMEOUT_DETECTED    : F6 BOK 대칭 (선반영, 이번 구현 미사용)
--   BOK_SETTLEMENT_FAILED   : F7 BOK 대칭 (선반영, 이번 구현 미사용)
-- =============================================================

ALTER TABLE status_history
    DROP CONSTRAINT chk_status_history_event_type;

ALTER TABLE status_history
    ADD CONSTRAINT chk_status_history_event_type
        CHECK (event_type IN (
            -- ── V9 기존 38개 (누락 없이 원문 그대로) ──────────────────────
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
            -- ── V13 신규 6개 (F4/F6/F7 + BOK 대칭 선반영) ────────────────
            'KFTC_REQUEST_FAILED',
            'KFTC_TIMEOUT_DETECTED',
            'KFTC_SETTLEMENT_FAILED',
            'OPERATOR_CANCEL_DECIDED',
            'BOK_TIMEOUT_DETECTED',
            'BOK_SETTLEMENT_FAILED'
        ));
