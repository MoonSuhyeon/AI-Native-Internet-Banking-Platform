-- =============================================================
-- V11__bok_settlement_transaction.sql
-- BOK(한은망) 거액이체 정산거래 테이블 생성
-- PostgreSQL 16 / Flyway
-- =============================================================

CREATE TABLE bok_settlement_transaction (

    -- ── 식별자 ──────────────────────────────────────────────────────────────
    settlement_transaction_id   VARCHAR(20)     NOT NULL,

    -- ── 결제지시 참조 (1:1, OUT=우리 PI, IN=상대 PI) ─────────────────────
    our_payment_instruction_id  VARCHAR(20)     NOT NULL,

    -- ── 방향 (OUT=타행송신, IN=타행수신) ─────────────────────────────────
    direction                   VARCHAR(5)      NOT NULL,

    -- ── 상대방 거래 참조 (IN 방향 시 상대 은행 결제지시 ID, nullable) ─────
    counterparty_payment_id     VARCHAR(50)     NULL,

    -- ── BOK 정산 식별 ──────────────────────────────────────────────────────
    bok_reference_no            VARCHAR(50)     NOT NULL,
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
    settlement_amount           DECIMAL(15,0)   NOT NULL,
    currency                    CHAR(3)         NOT NULL    DEFAULT 'KRW',

    -- ── 정산 상태 ──────────────────────────────────────────────────────────
    settlement_status           VARCHAR(20)     NOT NULL,
    reject_code                 VARCHAR(10)     NULL,
    reject_message              VARCHAR(200)    NULL,

    -- ── 정산 시각 (VARCHAR14 = yyyyMMddHHmmss) ───────────────────────────
    settlement_requested_at     VARCHAR(14)     NOT NULL,
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
    CONSTRAINT pk_bok_settlement_transaction
        PRIMARY KEY (settlement_transaction_id),

    -- ── 유니크 ─────────────────────────────────────────────────────────────
    CONSTRAINT uq_bst_payment_instruction
        UNIQUE (our_payment_instruction_id),
    CONSTRAINT uq_bst_bok_reference_no
        UNIQUE (bok_reference_no),

    -- ── 방향 CHECK (2개) ──────────────────────────────────────────────────
    CONSTRAINT chk_bst_direction
        CHECK (direction IN ('OUT', 'IN')),

    -- ── 정산상태 CHECK (5개) ──────────────────────────────────────────────
    CONSTRAINT chk_bst_settlement_status
        CHECK (settlement_status IN ('REQUESTED', 'ACK_RECEIVED', 'SETTLED', 'REJECTED', 'TIMEOUT')),

    -- ── 금액 CHECK ────────────────────────────────────────────────────────
    CONSTRAINT chk_bst_settlement_amount
        CHECK (settlement_amount >= 0),

    -- ── 망 CHECK (BOK 전용 테이블 — 단일값 고정) ─────────────────────────
    CONSTRAINT chk_bst_network
        CHECK (network IN ('BOK_CLEARING')),

    -- ── FK: 결제지시 ───────────────────────────────────────────────────────
    CONSTRAINT fk_bst_pi
        FOREIGN KEY (our_payment_instruction_id)
        REFERENCES payment_instruction (payment_instruction_id)
);


-- ── 테이블/컬럼 한글 코멘트 ──────────────────────────────────────────────────

COMMENT ON TABLE bok_settlement_transaction IS 'BOK한은망정산거래';

COMMENT ON COLUMN bok_settlement_transaction.settlement_transaction_id   IS '정산거래번호';
COMMENT ON COLUMN bok_settlement_transaction.our_payment_instruction_id  IS '결제지시번호(자행)';
COMMENT ON COLUMN bok_settlement_transaction.direction                   IS '방향(OUT=타행송신/IN=타행수신)';
COMMENT ON COLUMN bok_settlement_transaction.counterparty_payment_id     IS '상대방거래참조';
COMMENT ON COLUMN bok_settlement_transaction.bok_reference_no            IS 'BOK정산식별번호';
COMMENT ON COLUMN bok_settlement_transaction.sender_bank_clearing_id     IS '송신은행청산ID';
COMMENT ON COLUMN bok_settlement_transaction.receiver_bank_clearing_id   IS '수신은행청산ID';
COMMENT ON COLUMN bok_settlement_transaction.sender_bank_code            IS '송신은행코드';
COMMENT ON COLUMN bok_settlement_transaction.sender_account_no_snap      IS '송신계좌번호_스냅샷';
COMMENT ON COLUMN bok_settlement_transaction.sender_holder_name_snap     IS '송신예금주명_스냅샷';
COMMENT ON COLUMN bok_settlement_transaction.receiver_bank_code          IS '수신은행코드';
COMMENT ON COLUMN bok_settlement_transaction.receiver_account_no_snap    IS '수신계좌번호_스냅샷';
COMMENT ON COLUMN bok_settlement_transaction.receiver_holder_name_snap   IS '수신예금주명_스냅샷';
COMMENT ON COLUMN bok_settlement_transaction.settlement_amount           IS '정산금액';
COMMENT ON COLUMN bok_settlement_transaction.currency                    IS '통화';
COMMENT ON COLUMN bok_settlement_transaction.settlement_status           IS '정산상태';
COMMENT ON COLUMN bok_settlement_transaction.reject_code                 IS '거절코드';
COMMENT ON COLUMN bok_settlement_transaction.reject_message              IS '거절메시지';
COMMENT ON COLUMN bok_settlement_transaction.settlement_requested_at     IS '정산요청시각(yyyyMMddHHmmss)';
COMMENT ON COLUMN bok_settlement_transaction.ack_received_at             IS 'ACK수신시각(yyyyMMddHHmmss)';
COMMENT ON COLUMN bok_settlement_transaction.settled_at                  IS '정산완료시각(yyyyMMddHHmmss)';
COMMENT ON COLUMN bok_settlement_transaction.settlement_date             IS '정산일자(yyyyMMdd)';
COMMENT ON COLUMN bok_settlement_transaction.network                     IS '청산망종류';
COMMENT ON COLUMN bok_settlement_transaction.last_inquiry_at             IS '마지막조회시각';
COMMENT ON COLUMN bok_settlement_transaction.inquiry_count               IS '조회횟수';
COMMENT ON COLUMN bok_settlement_transaction.first_registered_at         IS '최초등록일시';
COMMENT ON COLUMN bok_settlement_transaction.first_registrant_id         IS '최초등록자식별번호';
COMMENT ON COLUMN bok_settlement_transaction.last_modified_at            IS '최종수정일시';
COMMENT ON COLUMN bok_settlement_transaction.last_modifier_id            IS '최종수정자식별번호';
