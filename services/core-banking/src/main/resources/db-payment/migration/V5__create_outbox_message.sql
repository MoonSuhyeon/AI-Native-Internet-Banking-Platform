-- =============================================================
-- V5__create_outbox_message.sql
-- Outbox메시지 테이블
-- PostgreSQL 16 / Flyway
-- =============================================================

CREATE TABLE outbox_message (

    -- ── 식별자 ──────────────────────────────────────────────────────────────
    message_id                          VARCHAR(20)     NOT NULL,

    -- ── 결제지시 참조 (항상 연관 — NOT NULL) ──────────────────────────────
    payment_instruction_id              VARCHAR(20)     NOT NULL,

    -- ── 이벤트 명세 ────────────────────────────────────────────────────────
    event_type                          VARCHAR(30)     NOT NULL,
    event_schema_version                VARCHAR(10)     NOT NULL,
    payload                             JSONB           NOT NULL,

    -- ── 발행 상태/재시도 ────────────────────────────────────────────────────
    publish_status                      VARCHAR(20)     NOT NULL        DEFAULT 'PENDING',
    attempt_count                       INT             NOT NULL        DEFAULT 0,

    -- ── 워커 폴링 기준 ─────────────────────────────────────────────────────
    available_at                        TIMESTAMP(3)    NOT NULL,
    last_error                          VARCHAR(500)    NULL,
    published_at                        TIMESTAMP(3)    NULL,

    -- ── 등록/수정 (외부 도메인 직원 ID — FK 없음) ─────────────────────────
    first_registered_at                 TIMESTAMP(3)    NOT NULL        DEFAULT CURRENT_TIMESTAMP(3),
    first_registrant_id                 VARCHAR(20)     NULL,
    last_modified_at                    TIMESTAMP(3)    NOT NULL        DEFAULT CURRENT_TIMESTAMP(3),
    last_modifier_id                    VARCHAR(20)     NULL,


    -- ── 기본 키 ────────────────────────────────────────────────────────────
    CONSTRAINT pk_outbox_message
        PRIMARY KEY (message_id),

    -- ── FK: 결제지시 (NOT NULL) ────────────────────────────────────────────
    CONSTRAINT fk_outbox_message_payment_instruction
        FOREIGN KEY (payment_instruction_id)
        REFERENCES payment_instruction (payment_instruction_id),

    -- ── 이벤트종류 CHECK (19개) ───────────────────────────────────────────
    CONSTRAINT chk_outbox_message_event_type
        CHECK (event_type IN (
            'PAYMENT_REQUESTED', 'PAYMENT_SCHEDULED', 'PAYMENT_SCHEDULE_CANCELED',
            'KFTC_REQUEST_SENT', 'KFTC_REJECTED',
            'BOK_REQUEST_SENT', 'BOK_REJECTED', 'BOK_CONFIRMED',
            'PAYMENT_REVERSED', 'PAYMENT_COMPLETED', 'PAYMENT_FAILED', 'PAYMENT_CANCELED',
            'INBOUND_RECEIVED',
            'KFTC_ACK_SENT', 'BOK_ACK_SENT',
            'KFTC_SETTLEMENT_SENT', 'BOK_CONFIRM_SENT',
            'KFTC_REJECT_SENT', 'BOK_REJECT_SENT'
        )),

    -- ── 발행상태 CHECK (3개) ──────────────────────────────────────────────
    CONSTRAINT chk_outbox_message_publish_status
        CHECK (publish_status IN ('PENDING', 'SENT', 'FAILED')),

    -- ── 시도횟수 CHECK ────────────────────────────────────────────────────
    CONSTRAINT chk_outbox_message_attempt_count
        CHECK (attempt_count >= 0)
);


-- ── 테이블/컬럼 한글 코멘트 (14개) ──────────────────────────────────────────

COMMENT ON TABLE outbox_message IS 'Outbox메시지';

COMMENT ON COLUMN outbox_message.message_id                 IS '메시지번호';
COMMENT ON COLUMN outbox_message.payment_instruction_id     IS '결제지시번호';
COMMENT ON COLUMN outbox_message.event_type                 IS '이벤트종류';
COMMENT ON COLUMN outbox_message.event_schema_version       IS '이벤트스키마버전';
COMMENT ON COLUMN outbox_message.payload                    IS '페이로드';
COMMENT ON COLUMN outbox_message.publish_status             IS '발행상태';
COMMENT ON COLUMN outbox_message.attempt_count              IS '시도횟수';
COMMENT ON COLUMN outbox_message.available_at               IS '처리가능시각';
COMMENT ON COLUMN outbox_message.last_error                 IS '마지막오류';
COMMENT ON COLUMN outbox_message.published_at               IS '발행시각';
COMMENT ON COLUMN outbox_message.first_registered_at        IS '최초등록일시';
COMMENT ON COLUMN outbox_message.first_registrant_id        IS '최초등록자식별번호';
COMMENT ON COLUMN outbox_message.last_modified_at           IS '최종수정일시';
COMMENT ON COLUMN outbox_message.last_modifier_id           IS '최종수정자식별번호';
