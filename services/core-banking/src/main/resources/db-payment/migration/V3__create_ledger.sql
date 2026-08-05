-- =============================================================
-- V3__create_ledger.sql
-- 계좌원장 테이블
-- PostgreSQL 16 / Flyway
-- ★ spec 본문 결함 처리:
--   - debit_credit: spec CHAR(6) → VARCHAR(20) (enum 컬럼 일관성)
--   - 테이블명: spec 'account_ledger' → 'ledger' (타 산출물 전체 기준)
-- =============================================================

CREATE TABLE ledger (

    -- ── 식별자 ──────────────────────────────────────────────────────────────
    ledger_id                           VARCHAR(20)     NOT NULL,

    -- ── 결제지시 참조 (이자/수기분개 시 NULL) ─────────────────────────────
    payment_instruction_id              VARCHAR(20)     NULL,

    -- ── 계좌 (외부 도메인 — FK 없음) ──────────────────────────────────────
    account_id                          VARCHAR(20)     NOT NULL,

    -- ── 역분개 원분개 참조 (self) ─────────────────────────────────────────
    original_ledger_id                  VARCHAR(20)     NULL,

    -- ── 분개 그룹 ──────────────────────────────────────────────────────────
    journal_no                          VARCHAR(20)     NOT NULL,

    -- ── 계좌 스냅샷 (박제 — 변경 불가) ────────────────────────────────────
    account_no_snap                     VARCHAR(30)     NOT NULL,
    holder_name_snap                    VARCHAR(60)     NOT NULL,

    -- ── 회계 구분 ──────────────────────────────────────────────────────────
    debit_credit                        VARCHAR(20)     NOT NULL,
    journal_type                        VARCHAR(30)     NOT NULL,

    -- ── 금액/잔액 ──────────────────────────────────────────────────────────
    amount                              DECIMAL(15,0)   NOT NULL,
    currency                            CHAR(3)         NOT NULL        DEFAULT 'KRW',
    balance_before                      DECIMAL(15,0)   NOT NULL,
    balance_after                       DECIMAL(15,0)   NOT NULL,

    -- ── 상대방 스냅샷 (박제, NULL 허용) ───────────────────────────────────
    counterparty_account_no_snap        VARCHAR(30)     NULL,
    counterparty_bank_code_snap         CHAR(3)         NULL,
    counterparty_holder_name_snap       VARCHAR(60)     NULL,

    -- ── 일자 ───────────────────────────────────────────────────────────────
    transaction_date                    VARCHAR(8)      NOT NULL,
    posting_date                        VARCHAR(8)      NOT NULL,
    value_date                          VARCHAR(8)      NOT NULL,

    -- ── 기장 시각/적요 ─────────────────────────────────────────────────────
    posted_at                           TIMESTAMP(3)    NOT NULL,
    system_description                  VARCHAR(100)    NOT NULL,
    passbook_memo_snap                  VARCHAR(100)    NULL,

    -- ── 역분개 ─────────────────────────────────────────────────────────────
    is_reversal                         BOOLEAN         NOT NULL        DEFAULT FALSE,
    reversal_reason                     VARCHAR(20)     NULL,

    -- ── 기장 상태 ──────────────────────────────────────────────────────────
    posting_status                      VARCHAR(20)     NOT NULL        DEFAULT 'PENDING',

    -- ── 등록/수정 (외부 도메인 직원 ID — FK 없음) ─────────────────────────
    first_registered_at                 TIMESTAMP(3)    NOT NULL        DEFAULT CURRENT_TIMESTAMP(3),
    first_registrant_id                 VARCHAR(20)     NULL,
    last_modified_at                    TIMESTAMP(3)    NOT NULL        DEFAULT CURRENT_TIMESTAMP(3),
    last_modifier_id                    VARCHAR(20)     NULL,


    -- ── 기본 키 ────────────────────────────────────────────────────────────
    CONSTRAINT pk_ledger
        PRIMARY KEY (ledger_id),

    -- ── FK: 결제지시 (NULL 허용) ───────────────────────────────────────────
    CONSTRAINT fk_ledger_payment_instruction
        FOREIGN KEY (payment_instruction_id)
        REFERENCES payment_instruction (payment_instruction_id),

    -- ── FK: 원분개 self (NULL 허용) ────────────────────────────────────────
    CONSTRAINT fk_ledger_original
        FOREIGN KEY (original_ledger_id)
        REFERENCES ledger (ledger_id),

    -- ── 차변대변 CHECK (2개) ──────────────────────────────────────────────
    CONSTRAINT chk_ledger_debit_credit
        CHECK (debit_credit IN ('DEBIT', 'CREDIT')),

    -- ── 분개종류 CHECK (9개) ──────────────────────────────────────────────
    CONSTRAINT chk_ledger_journal_type
        CHECK (journal_type IN (
            'TRANSFER_OUT', 'TRANSFER_IN', 'CLEARING_PENDING', 'FEE', 'FEE_INCOME',
            'REVERSAL_TRANSFER_OUT', 'REVERSAL_CLEARING_PENDING',
            'REVERSAL_FEE', 'REVERSAL_FEE_INCOME'
        )),

    -- ── 기장상태 CHECK (3개) ──────────────────────────────────────────────
    CONSTRAINT chk_ledger_posting_status
        CHECK (posting_status IN ('PENDING', 'POSTED', 'CANCELED')),

    -- ── 역분개사유 CHECK (7개, NULL 허용) ────────────────────────────────
    CONSTRAINT chk_ledger_reversal_reason
        CHECK (reversal_reason IS NULL OR reversal_reason IN (
            'PUBLISH_FAILURE', 'SYSTEM_FAILURE', 'COMPENSATION',
            'KFTC_REJECTION', 'BOK_REJECTION', 'SETTLEMENT_FAILURE', 'OPERATOR'
        )),

    -- ── 금액 CHECK ───────────────────────────────────────────────────────
    CONSTRAINT chk_ledger_amount
        CHECK (amount >= 0),
    CONSTRAINT chk_ledger_balance_before
        CHECK (balance_before >= 0),
    CONSTRAINT chk_ledger_balance_after
        CHECK (balance_after >= 0),

    -- ── 역분개 일관성 CHECK ───────────────────────────────────────────────
    CONSTRAINT chk_ledger_reversal_original_consistency
        CHECK (is_reversal = FALSE OR original_ledger_id IS NOT NULL),
    CONSTRAINT chk_ledger_reversal_reason_consistency
        CHECK (is_reversal = FALSE OR reversal_reason IS NOT NULL)
);


-- ── 테이블/컬럼 한글 코멘트 (29개) ──────────────────────────────────────────

COMMENT ON TABLE ledger IS '계좌원장';

COMMENT ON COLUMN ledger.ledger_id                          IS '분개번호';
COMMENT ON COLUMN ledger.payment_instruction_id             IS '결제지시번호';
COMMENT ON COLUMN ledger.account_id                         IS '계좌번호';
COMMENT ON COLUMN ledger.original_ledger_id                 IS '원분개참조';
COMMENT ON COLUMN ledger.journal_no                         IS '회계번호';
COMMENT ON COLUMN ledger.account_no_snap                    IS '계좌번호_스냅샷';
COMMENT ON COLUMN ledger.holder_name_snap                   IS '예금주명_스냅샷';
COMMENT ON COLUMN ledger.debit_credit                       IS '차변대변구분';
COMMENT ON COLUMN ledger.journal_type                       IS '분개종류';
COMMENT ON COLUMN ledger.amount                             IS '금액';
COMMENT ON COLUMN ledger.currency                           IS '통화';
COMMENT ON COLUMN ledger.balance_before                     IS '분개직전잔액';
COMMENT ON COLUMN ledger.balance_after                      IS '분개직후잔액';
COMMENT ON COLUMN ledger.counterparty_account_no_snap       IS '상대계좌번호_스냅샷';
COMMENT ON COLUMN ledger.counterparty_bank_code_snap        IS '상대은행코드_스냅샷';
COMMENT ON COLUMN ledger.counterparty_holder_name_snap      IS '상대예금주명_스냅샷';
COMMENT ON COLUMN ledger.transaction_date                   IS '거래일자';
COMMENT ON COLUMN ledger.posting_date                       IS '기장일자';
COMMENT ON COLUMN ledger.value_date                         IS '자금가용일';
COMMENT ON COLUMN ledger.posted_at                          IS '기장시각';
COMMENT ON COLUMN ledger.system_description                 IS '시스템적요';
COMMENT ON COLUMN ledger.passbook_memo_snap                 IS '통장에찍히는메모_스냅샷';
COMMENT ON COLUMN ledger.is_reversal                        IS '역분개여부';
COMMENT ON COLUMN ledger.reversal_reason                    IS '역분개사유';
COMMENT ON COLUMN ledger.posting_status                     IS '기장상태';
COMMENT ON COLUMN ledger.first_registered_at                IS '최초등록일시';
COMMENT ON COLUMN ledger.first_registrant_id                IS '최초등록자식별번호';
COMMENT ON COLUMN ledger.last_modified_at                   IS '최종수정일시';
COMMENT ON COLUMN ledger.last_modifier_id                   IS '최종수정자식별번호';
