-- =============================================================
-- V6__create_status_history.sql
-- 상태이력 테이블 (append-only audit log)
-- PostgreSQL 16 / Flyway
-- =============================================================

CREATE TABLE status_history (

    -- ── 식별자 ──────────────────────────────────────────────────────────────
    history_id                          VARCHAR(20)     NOT NULL,

    -- ── 결제지시 참조 (항상 연관 — NOT NULL) ──────────────────────────────
    payment_instruction_id              VARCHAR(20)     NOT NULL,

    -- ── 관련 외부호출 (트리거가 외부호출인 경우만) ────────────────────────
    related_external_call_id            VARCHAR(20)     NULL,

    -- ── 순번 ───────────────────────────────────────────────────────────────
    sequence_in_payment                 INT             NOT NULL,

    -- ── 상태 전이 ──────────────────────────────────────────────────────────
    previous_status                     VARCHAR(20)     NULL,
    next_status                         VARCHAR(20)     NOT NULL,

    -- ── 이벤트 ─────────────────────────────────────────────────────────────
    event_type                          VARCHAR(30)     NOT NULL,
    reason_code                         VARCHAR(10)     NULL,
    reason_message                      VARCHAR(200)    NULL,

    -- ── 트리거 주체 (외부 도메인 사용자 — FK 없음) ────────────────────────
    triggered_by                        VARCHAR(20)     NOT NULL,
    operator_id                         VARCHAR(20)     NULL,

    -- ── 이벤트 페이로드 ────────────────────────────────────────────────────
    payload_snapshot                    JSONB           NULL,

    -- ── 시각 ───────────────────────────────────────────────────────────────
    event_occurred_at                   TIMESTAMP(3)    NOT NULL,
    db_recorded_at                      TIMESTAMP(3)    NOT NULL        DEFAULT CURRENT_TIMESTAMP(3),

    -- ── 등록/수정 (외부 도메인 직원 ID — FK 없음) ─────────────────────────
    first_registered_at                 TIMESTAMP(3)    NOT NULL        DEFAULT CURRENT_TIMESTAMP(3),
    first_registrant_id                 VARCHAR(20)     NULL,
    last_modified_at                    TIMESTAMP(3)    NOT NULL        DEFAULT CURRENT_TIMESTAMP(3),
    last_modifier_id                    VARCHAR(20)     NULL,


    -- ── 기본 키 ────────────────────────────────────────────────────────────
    CONSTRAINT pk_status_history
        PRIMARY KEY (history_id),

    -- ── 유니크: 결제지시 내 순번 조합 ─────────────────────────────────────
    CONSTRAINT uq_status_history_payment_sequence
        UNIQUE (payment_instruction_id, sequence_in_payment),

    -- ── FK: 결제지시 (NOT NULL) ────────────────────────────────────────────
    CONSTRAINT fk_status_history_payment_instruction
        FOREIGN KEY (payment_instruction_id)
        REFERENCES payment_instruction (payment_instruction_id),

    -- ── FK: 관련 외부호출 (NULL 허용) ─────────────────────────────────────
    CONSTRAINT fk_status_history_external_call
        FOREIGN KEY (related_external_call_id)
        REFERENCES external_call (call_id),

    -- ── 이전상태 CHECK (9개, NULL 허용) ───────────────────────────────────
    CONSTRAINT chk_status_history_previous_status
        CHECK (previous_status IS NULL OR previous_status IN (
            'DRAFT', 'AUTHORIZED', 'SCHEDULED', 'PROCESSING',
            'CLEARING', 'REVERSING', 'COMPLETED', 'FAILED', 'CANCELED'
        )),

    -- ── 다음상태 CHECK (9개) ──────────────────────────────────────────────
    CONSTRAINT chk_status_history_next_status
        CHECK (next_status IN (
            'DRAFT', 'AUTHORIZED', 'SCHEDULED', 'PROCESSING',
            'CLEARING', 'REVERSING', 'COMPLETED', 'FAILED', 'CANCELED'
        )),

    -- ── 이벤트종류 CHECK (37개) ───────────────────────────────────────────
    CONSTRAINT chk_status_history_event_type
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
            'COMPENSATION_STARTED', 'COMPENSATION_COMPLETED', 'COMPENSATION_FAILED',
            'KFTC_ACK_SENT', 'BOK_ACK_SENT',
            'KFTC_SETTLEMENT_SENT', 'BOK_CONFIRM_SENT',
            'KFTC_REJECT_SENT', 'BOK_REJECT_SENT',
            'INBOUND_VALIDATION_PASSED', 'INBOUND_VALIDATION_FAILED'
        )),

    -- ── 트리거주체 CHECK (6개) ────────────────────────────────────────────
    CONSTRAINT chk_status_history_triggered_by
        CHECK (triggered_by IN ('USER', 'SYSTEM', 'KFTC', 'BOK', 'OPERATOR', 'SCHEDULER')),

    -- ── 순번 CHECK ───────────────────────────────────────────────────────
    CONSTRAINT chk_status_history_sequence_in_payment
        CHECK (sequence_in_payment >= 1),

    -- ── 운영자 일관성 CHECK ───────────────────────────────────────────────
    CONSTRAINT chk_status_history_operator_consistency
        CHECK (triggered_by <> 'OPERATOR' OR operator_id IS NOT NULL)
);


-- ── 테이블/컬럼 한글 코멘트 (18개) ──────────────────────────────────────────

COMMENT ON TABLE status_history IS '상태이력';

COMMENT ON COLUMN status_history.history_id                 IS '상태이력번호';
COMMENT ON COLUMN status_history.payment_instruction_id     IS '결제지시번호';
COMMENT ON COLUMN status_history.related_external_call_id   IS '관련외부호출번호';
COMMENT ON COLUMN status_history.sequence_in_payment        IS '결제지시내순번';
COMMENT ON COLUMN status_history.previous_status            IS '이전상태';
COMMENT ON COLUMN status_history.next_status                IS '다음상태';
COMMENT ON COLUMN status_history.event_type                 IS '이벤트종류';
COMMENT ON COLUMN status_history.reason_code                IS '사유코드';
COMMENT ON COLUMN status_history.reason_message             IS '사유메시지';
COMMENT ON COLUMN status_history.triggered_by               IS '트리거주체';
COMMENT ON COLUMN status_history.operator_id                IS '운영자ID';
COMMENT ON COLUMN status_history.payload_snapshot           IS '페이로드스냅샷';
COMMENT ON COLUMN status_history.event_occurred_at          IS '이벤트발생시각';
COMMENT ON COLUMN status_history.db_recorded_at             IS 'DB기록시각';
COMMENT ON COLUMN status_history.first_registered_at        IS '최초등록일시';
COMMENT ON COLUMN status_history.first_registrant_id        IS '최초등록자식별번호';
COMMENT ON COLUMN status_history.last_modified_at           IS '최종수정일시';
COMMENT ON COLUMN status_history.last_modifier_id           IS '최종수정자식별번호';
