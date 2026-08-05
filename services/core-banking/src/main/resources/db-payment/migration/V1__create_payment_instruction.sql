-- =============================================================
-- V1__create_payment_instruction.sql
-- 결제지시 마스터 테이블
-- PostgreSQL 16 / Flyway
-- =============================================================

CREATE TABLE payment_instruction (

    -- ── 식별자 ──────────────────────────────────────────────────────────────
    payment_instruction_id              VARCHAR(20)     NOT NULL,
    idempotency_key                     VARCHAR(50)     NOT NULL,

    -- ── 송신 (외부 도메인 참조 — FK 없음, 박제 의도) ─────────────────────────
    sender_user_id                      VARCHAR(20)     NULL,
    sender_account_id                   VARCHAR(20)     NULL,
    auth_token_id                       VARCHAR(20)     NULL,

    -- ── 역전/취소 원거래 참조 (self) ───────────────────────────────────────
    original_payment_id                 VARCHAR(20)     NULL,

    -- ── 거래 식별 ───────────────────────────────────────────────────────────
    transaction_no                      VARCHAR(30)     NOT NULL,

    -- ── 송신 스냅샷 (박제 — 변경 불가) ─────────────────────────────────────
    sender_account_no_snap              VARCHAR(30)     NOT NULL,
    sender_account_alias_snap           VARCHAR(60)     NULL,

    -- ── 수신 ───────────────────────────────────────────────────────────────
    receiver_bank_code                  CHAR(3)         NOT NULL,
    receiver_account_no                 VARCHAR(30)     NOT NULL,
    receiver_holder_name_snap           VARCHAR(60)     NOT NULL,
    holder_inquiry_at                   TIMESTAMP(3)    NOT NULL,

    -- ── 라우팅 ─────────────────────────────────────────────────────────────
    is_intra_bank                       BOOLEAN         NOT NULL,
    routing_network_type                VARCHAR(20)     NOT NULL,

    -- ── 금액 ───────────────────────────────────────────────────────────────
    transfer_amount                     DECIMAL(15,0)   NOT NULL,
    fee_amount                          DECIMAL(15,0)   NOT NULL        DEFAULT 0,

    -- ── 통장 표시 ──────────────────────────────────────────────────────────
    receiver_passbook_sender_display    VARCHAR(60)     NULL,
    receiver_memo                       VARCHAR(100)    NULL,
    sender_memo                         VARCHAR(100)    NULL,

    -- ── 상태 ───────────────────────────────────────────────────────────────
    status                              VARCHAR(20)     NOT NULL,
    failure_category                    VARCHAR(30)     NULL,

    -- ── 채널 ───────────────────────────────────────────────────────────────
    channel                             VARCHAR(20)     NOT NULL,

    -- ── 시각 ───────────────────────────────────────────────────────────────
    requested_at                        TIMESTAMP(3)    NOT NULL,
    completed_at                        TIMESTAMP(3)    NULL,
    business_date                       VARCHAR(8)      NOT NULL,
    next_retry_at                       TIMESTAMP(3)    NULL,
    next_timeout_at                     TIMESTAMP(3)    NULL,

    -- ── 낙관적락 ────────────────────────────────────────────────────────────
    version                             INT             NOT NULL        DEFAULT 0,

    -- ── 트리거/예약 ─────────────────────────────────────────────────────────
    trigger_source                      VARCHAR(20)     NOT NULL        DEFAULT 'USER',
    is_scheduled                        BOOLEAN         NOT NULL        DEFAULT FALSE,
    scheduled_execution_at              TIMESTAMP(3)    NULL,

    -- ── 등록/수정 (외부 도메인 직원 ID — FK 없음) ─────────────────────────
    first_registered_at                 TIMESTAMP(3)    NOT NULL        DEFAULT CURRENT_TIMESTAMP(3),
    first_registrant_id                 VARCHAR(20)     NULL,
    last_modified_at                    TIMESTAMP(3)    NOT NULL    DEFAULT CURRENT_TIMESTAMP(3),
    last_modifier_id                    VARCHAR(20)     NULL,


    -- ── 기본 키 ────────────────────────────────────────────────────────────
    CONSTRAINT pk_payment_instruction
        PRIMARY KEY (payment_instruction_id),

    -- ── 유니크 ─────────────────────────────────────────────────────────────
    CONSTRAINT uq_payment_instruction_idempotency_key
        UNIQUE (idempotency_key),
    CONSTRAINT uq_payment_instruction_transaction_no
        UNIQUE (transaction_no),
    CONSTRAINT uq_payment_instruction_auth_token_id
        UNIQUE (auth_token_id),

    -- ── 자기 참조 FK ───────────────────────────────────────────────────────
    CONSTRAINT fk_payment_instruction_original
        FOREIGN KEY (original_payment_id)
        REFERENCES payment_instruction (payment_instruction_id),

    -- ── 진행상태 CHECK (9개) ──────────────────────────────────────────────
    CONSTRAINT chk_payment_instruction_status
        CHECK (status IN (
            'DRAFT', 'AUTHORIZED', 'SCHEDULED', 'PROCESSING',
            'CLEARING', 'REVERSING', 'COMPLETED', 'FAILED', 'CANCELED'
        )),

    -- ── 실패분류 CHECK (14개, NULL 허용) ─────────────────────────────────
    CONSTRAINT chk_payment_instruction_failure_category
        CHECK (failure_category IS NULL OR failure_category IN (
            'INSUFFICIENT_BALANCE', 'LIMIT_EXCEEDED', 'AUTH_FAILED', 'OWNER_INQUIRY_FAILED',
            'KFTC_REJECTED', 'KFTC_TIMEOUT', 'BOK_REJECTED', 'BOK_TIMEOUT',
            'INVALID_ACCOUNT', 'FRAUD_DETECTED', 'SYSTEM_ERROR',
            'ACCOUNT_RESTRICTED', 'ACCOUNT_NOT_FOUND', 'ACCOUNT_CLOSED'
        )),

    -- ── 채널 CHECK (6개) ─────────────────────────────────────────────────
    CONSTRAINT chk_payment_instruction_channel
        CHECK (channel IN ('WEB', 'MOBILE', 'BRANCH', 'ATM', 'OPEN_BANKING', 'INBOUND')),

    -- ── 트리거주체 CHECK (5개) ───────────────────────────────────────────
    CONSTRAINT chk_payment_instruction_trigger_source
        CHECK (trigger_source IN (
            'USER', 'AUTO_TRANSFER', 'SCHEDULER', 'OPERATOR', 'COUNTERPARTY_BANK'
        )),

    -- ── 라우팅망 CHECK (3개) ─────────────────────────────────────────────
    CONSTRAINT chk_payment_instruction_routing_network_type
        CHECK (routing_network_type IN ('INTERNAL', 'KFTC', 'BOK')),

    -- ── 금액 CHECK ───────────────────────────────────────────────────────
    CONSTRAINT chk_payment_instruction_transfer_amount
        CHECK (transfer_amount >= 0),
    CONSTRAINT chk_payment_instruction_fee_amount
        CHECK (fee_amount >= 0),

    -- ── version CHECK ────────────────────────────────────────────────────
    CONSTRAINT chk_payment_instruction_version
        CHECK (version >= 0),

    -- ── 예약 일관성 CHECK ────────────────────────────────────────────────
    CONSTRAINT chk_payment_instruction_scheduled_consistency
        CHECK (is_scheduled = FALSE OR scheduled_execution_at IS NOT NULL)
);


-- ── 테이블/컬럼 한글 코멘트 (36개) ──────────────────────────────────────────

COMMENT ON TABLE payment_instruction IS '결제지시';

COMMENT ON COLUMN payment_instruction.payment_instruction_id            IS '결제지시번호';
COMMENT ON COLUMN payment_instruction.idempotency_key                   IS '연결된멱등키값';
COMMENT ON COLUMN payment_instruction.sender_user_id                    IS '송신고객번호';
COMMENT ON COLUMN payment_instruction.sender_account_id                 IS '송신계좌번호';
COMMENT ON COLUMN payment_instruction.auth_token_id                     IS '인증토큰번호';
COMMENT ON COLUMN payment_instruction.original_payment_id               IS '원거래참조';
COMMENT ON COLUMN payment_instruction.transaction_no                    IS '거래번호';
COMMENT ON COLUMN payment_instruction.sender_account_no_snap            IS '송신계좌번호_스냅샷';
COMMENT ON COLUMN payment_instruction.sender_account_alias_snap         IS '송신계좌별명_스냅샷';
COMMENT ON COLUMN payment_instruction.receiver_bank_code                IS '수신은행코드';
COMMENT ON COLUMN payment_instruction.receiver_account_no               IS '수신계좌번호';
COMMENT ON COLUMN payment_instruction.receiver_holder_name_snap         IS '수신예금주명_스냅샷';
COMMENT ON COLUMN payment_instruction.holder_inquiry_at                 IS '예금주조회시각';
COMMENT ON COLUMN payment_instruction.is_intra_bank                     IS '자행이체여부';
COMMENT ON COLUMN payment_instruction.routing_network_type              IS '라우팅망종류';
COMMENT ON COLUMN payment_instruction.transfer_amount                   IS '이체금액';
COMMENT ON COLUMN payment_instruction.fee_amount                        IS '수수료';
COMMENT ON COLUMN payment_instruction.receiver_passbook_sender_display  IS '수신통장_송신자표시명';
COMMENT ON COLUMN payment_instruction.receiver_memo                     IS '받는분통장메모';
COMMENT ON COLUMN payment_instruction.sender_memo                       IS '내통장메모';
COMMENT ON COLUMN payment_instruction.status                            IS '진행상태';
COMMENT ON COLUMN payment_instruction.failure_category                  IS '실패분류';
COMMENT ON COLUMN payment_instruction.channel                           IS '채널';
COMMENT ON COLUMN payment_instruction.requested_at                      IS '요청시각';
COMMENT ON COLUMN payment_instruction.completed_at                      IS '완료시각';
COMMENT ON COLUMN payment_instruction.business_date                     IS '영업일자';
COMMENT ON COLUMN payment_instruction.next_retry_at                     IS '다음재시도시각';
COMMENT ON COLUMN payment_instruction.next_timeout_at                   IS '다음타임아웃시각';
COMMENT ON COLUMN payment_instruction.version                           IS '낙관적락버전';
COMMENT ON COLUMN payment_instruction.trigger_source                    IS '트리거주체';
COMMENT ON COLUMN payment_instruction.is_scheduled                      IS '예약여부';
COMMENT ON COLUMN payment_instruction.scheduled_execution_at            IS '예약실행시각';
COMMENT ON COLUMN payment_instruction.first_registered_at               IS '최초등록일시';
COMMENT ON COLUMN payment_instruction.first_registrant_id               IS '최초등록자식별번호';
COMMENT ON COLUMN payment_instruction.last_modified_at                  IS '최종수정일시';
COMMENT ON COLUMN payment_instruction.last_modifier_id                  IS '최종수정자식별번호';
