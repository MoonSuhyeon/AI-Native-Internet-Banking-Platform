-- =============================================================
-- V10__kftc_clearing_transaction.sql
-- 1-A: KFTC 청산거래 테이블 생성
-- 1-B: outbox_message event_type CHECK에 KFTC_SETTLED 추가
-- =============================================================

-- ─────────────────────────────────────────────────────────────
-- 1-A: kftc_clearing_transaction
-- ─────────────────────────────────────────────────────────────

CREATE TABLE kftc_clearing_transaction (

    -- ── 식별자 ──────────────────────────────────────────────────────────────
    clearing_transaction_id     VARCHAR(20)     NOT NULL,

    -- ── 결제지시 참조 (1:1, OUT=우리 PI, IN=상대 PI) ─────────────────────
    our_payment_instruction_id  VARCHAR(20)     NOT NULL,

    -- ── 방향 (OUT=타행송신, IN=타행수신) ─────────────────────────────────
    direction                   VARCHAR(5)      NOT NULL,

    -- ── 상대방 거래 참조 (IN 방향 시 상대 은행 결제지시 ID, nullable) ─────
    counterparty_payment_id     VARCHAR(50)     NULL,

    -- ── KFTC 청산 식별 ──────────────────────────────────────────────────
    clearing_no                 VARCHAR(50)     NOT NULL,
    sender_bank_clearing_id     VARCHAR(50)     NULL,
    receiver_bank_clearing_id   VARCHAR(50)     NULL,

    -- ── 송신 박제 ──────────────────────────────────────────────────────────
    sender_bank_code            CHAR(3)         NOT NULL,
    sender_account_no_snap      VARCHAR(30)     NOT NULL,
    sender_holder_name_snap     VARCHAR(60)     NOT NULL,

    -- ── 수신 박제 ──────────────────────────────────────────────────────────
    receiver_bank_code          CHAR(3)         NOT NULL,
    receiver_account_no_snap    VARCHAR(30)     NOT NULL,
    receiver_holder_name_snap   VARCHAR(60)     NOT NULL,

    -- ── 금액 ───────────────────────────────────────────────────────────────
    clearing_amount             DECIMAL(15,0)   NOT NULL,
    currency                    CHAR(3)         NOT NULL    DEFAULT 'KRW',

    -- ── 청산 상태 ──────────────────────────────────────────────────────────
    clearing_status             VARCHAR(20)     NOT NULL,
    reject_code                 VARCHAR(10)     NULL,
    reject_message              VARCHAR(200)    NULL,

    -- ── 청산 시각 (VARCHAR14 = yyyyMMddHHmmss) ───────────────────────────
    clearing_requested_at       VARCHAR(14)     NOT NULL,
    ack_received_at             VARCHAR(14)     NULL,
    settled_at                  VARCHAR(14)     NULL,
    settlement_date             VARCHAR(8)      NULL,

    -- ── 망 ─────────────────────────────────────────────────────────────────
    network                     VARCHAR(30)     NOT NULL,

    -- ── 조회 추적 ──────────────────────────────────────────────────────────
    last_inquiry_at             TIMESTAMP(3)    NULL,
    inquiry_count               INT             NOT NULL    DEFAULT 0,

    -- ── 등록/수정 ──────────────────────────────────────────────────────────
    first_registered_at         TIMESTAMP(3)    NOT NULL    DEFAULT CURRENT_TIMESTAMP(3),
    first_registrant_id         VARCHAR(20)     NULL,
    last_modified_at            TIMESTAMP(3)    NOT NULL    DEFAULT CURRENT_TIMESTAMP(3),
    last_modifier_id            VARCHAR(20)     NULL,


    -- ── 기본 키 ────────────────────────────────────────────────────────────
    CONSTRAINT pk_kftc_clearing_transaction
        PRIMARY KEY (clearing_transaction_id),

    -- ── 유니크 ─────────────────────────────────────────────────────────────
    CONSTRAINT uq_kct_payment_instruction
        UNIQUE (our_payment_instruction_id),
    CONSTRAINT uq_kct_clearing_no
        UNIQUE (clearing_no),

    -- ── 방향 CHECK (2개) ──────────────────────────────────────────────────
    CONSTRAINT chk_kct_direction
        CHECK (direction IN ('OUT', 'IN')),

    -- ── 청산상태 CHECK (5개) ──────────────────────────────────────────────
    CONSTRAINT chk_kct_clearing_status
        CHECK (clearing_status IN ('REQUESTED', 'ACK', 'SETTLED', 'REJECTED', 'TIMEOUT')),

    -- ── 금액 CHECK ────────────────────────────────────────────────────────
    CONSTRAINT chk_kct_clearing_amount
        CHECK (clearing_amount >= 0),

    -- ── 망 CHECK (4개 — 컬럼명세서 EBANKING/INTERBANK + 시나리오 KFTC_CLEARING/BOK_CLEARING) ──
    CONSTRAINT chk_kct_network
        CHECK (network IN ('KFTC_CLEARING', 'INTERBANK', 'EBANKING', 'BOK_CLEARING')),

    -- ── FK: 결제지시 ───────────────────────────────────────────────────────
    CONSTRAINT fk_kct_pi
        FOREIGN KEY (our_payment_instruction_id)
        REFERENCES payment_instruction (payment_instruction_id)
);


-- ── 테이블/컬럼 한글 코멘트 ──────────────────────────────────────────────────

COMMENT ON TABLE kftc_clearing_transaction IS 'KFTC청산거래';

COMMENT ON COLUMN kftc_clearing_transaction.clearing_transaction_id     IS '청산거래번호';
COMMENT ON COLUMN kftc_clearing_transaction.our_payment_instruction_id  IS '결제지시번호(자행)';
COMMENT ON COLUMN kftc_clearing_transaction.direction                   IS '방향(OUT=타행송신/IN=타행수신)';
COMMENT ON COLUMN kftc_clearing_transaction.counterparty_payment_id     IS '상대방거래참조';
COMMENT ON COLUMN kftc_clearing_transaction.clearing_no                 IS 'KFTC청산식별번호';
COMMENT ON COLUMN kftc_clearing_transaction.sender_bank_clearing_id     IS '송신은행청산ID';
COMMENT ON COLUMN kftc_clearing_transaction.receiver_bank_clearing_id   IS '수신은행청산ID';
COMMENT ON COLUMN kftc_clearing_transaction.sender_bank_code            IS '송신은행코드';
COMMENT ON COLUMN kftc_clearing_transaction.sender_account_no_snap      IS '송신계좌번호_스냅샷';
COMMENT ON COLUMN kftc_clearing_transaction.sender_holder_name_snap     IS '송신예금주명_스냅샷';
COMMENT ON COLUMN kftc_clearing_transaction.receiver_bank_code          IS '수신은행코드';
COMMENT ON COLUMN kftc_clearing_transaction.receiver_account_no_snap    IS '수신계좌번호_스냅샷';
COMMENT ON COLUMN kftc_clearing_transaction.receiver_holder_name_snap   IS '수신예금주명_스냅샷';
COMMENT ON COLUMN kftc_clearing_transaction.clearing_amount             IS '청산금액';
COMMENT ON COLUMN kftc_clearing_transaction.currency                    IS '통화';
COMMENT ON COLUMN kftc_clearing_transaction.clearing_status             IS '청산상태';
COMMENT ON COLUMN kftc_clearing_transaction.reject_code                 IS '거절코드';
COMMENT ON COLUMN kftc_clearing_transaction.reject_message              IS '거절메시지';
COMMENT ON COLUMN kftc_clearing_transaction.clearing_requested_at       IS '청산요청시각(yyyyMMddHHmmss)';
COMMENT ON COLUMN kftc_clearing_transaction.ack_received_at             IS 'ACK수신시각(yyyyMMddHHmmss)';
COMMENT ON COLUMN kftc_clearing_transaction.settled_at                  IS '정산완료시각(yyyyMMddHHmmss)';
COMMENT ON COLUMN kftc_clearing_transaction.settlement_date             IS '정산일자(yyyyMMdd)';
COMMENT ON COLUMN kftc_clearing_transaction.network                     IS '청산망종류';
COMMENT ON COLUMN kftc_clearing_transaction.last_inquiry_at             IS '마지막조회시각';
COMMENT ON COLUMN kftc_clearing_transaction.inquiry_count               IS '조회횟수';
COMMENT ON COLUMN kftc_clearing_transaction.first_registered_at         IS '최초등록일시';
COMMENT ON COLUMN kftc_clearing_transaction.first_registrant_id         IS '최초등록자식별번호';
COMMENT ON COLUMN kftc_clearing_transaction.last_modified_at            IS '최종수정일시';
COMMENT ON COLUMN kftc_clearing_transaction.last_modifier_id            IS '최종수정자식별번호';


-- =============================================================
-- 1-B: outbox_message event_type CHECK에 KFTC_SETTLED 추가
-- 기존 V5 19개 그대로 + KFTC_SETTLED 1개 = 20개
-- =============================================================

ALTER TABLE outbox_message
    DROP CONSTRAINT chk_outbox_message_event_type;

ALTER TABLE outbox_message
    ADD CONSTRAINT chk_outbox_message_event_type
        CHECK (event_type IN (
            'PAYMENT_REQUESTED', 'PAYMENT_SCHEDULED', 'PAYMENT_SCHEDULE_CANCELED',
            'KFTC_REQUEST_SENT', 'KFTC_REJECTED', 'KFTC_SETTLED',
            'BOK_REQUEST_SENT', 'BOK_REJECTED', 'BOK_CONFIRMED',
            'PAYMENT_REVERSED', 'PAYMENT_COMPLETED', 'PAYMENT_FAILED', 'PAYMENT_CANCELED',
            'INBOUND_RECEIVED',
            'KFTC_ACK_SENT', 'BOK_ACK_SENT',
            'KFTC_SETTLEMENT_SENT', 'BOK_CONFIRM_SENT',
            'KFTC_REJECT_SENT', 'BOK_REJECT_SENT'
        ));
