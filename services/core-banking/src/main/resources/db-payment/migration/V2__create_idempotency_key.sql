-- =============================================================
-- V2__create_idempotency_key.sql
-- 멱등키 테이블 + payment_instruction FK 마무리
-- PostgreSQL 16 / Flyway
-- =============================================================

CREATE TABLE idempotency_key (

    -- ── 식별자 ──────────────────────────────────────────────────────────────
    idempotency_key                     VARCHAR(50)     NOT NULL,

    -- ── 클라이언트 ─────────────────────────────────────────────────────────
    client_id                           VARCHAR(30)     NOT NULL,

    -- ── 요청 무결성 ────────────────────────────────────────────────────────
    request_hash                        VARCHAR(100)    NOT NULL,

    -- ── 상태 ───────────────────────────────────────────────────────────────
    idempotency_status                  VARCHAR(20)     NOT NULL        DEFAULT 'PROCESSING',

    -- ── 응답 박제 ──────────────────────────────────────────────────────────
    first_response_snap                 JSONB           NULL,

    -- ── 재시도 ─────────────────────────────────────────────────────────────
    retry_count                         INT             NOT NULL        DEFAULT 0,

    -- ── 시각 ───────────────────────────────────────────────────────────────
    first_received_at                   TIMESTAMP(3)    NOT NULL,
    last_received_at                    TIMESTAMP(3)    NOT NULL,
    expires_at                          TIMESTAMP(3)    NOT NULL,

    -- ── 등록/수정 (외부 도메인 직원 ID — FK 없음) ─────────────────────────
    first_registered_at                 TIMESTAMP(3)    NOT NULL        DEFAULT CURRENT_TIMESTAMP(3),
    first_registrant_id                 VARCHAR(20)     NULL,
    last_modified_at                    TIMESTAMP(3)    NOT NULL        DEFAULT CURRENT_TIMESTAMP(3),
    last_modifier_id                    VARCHAR(20)     NULL,


    -- ── 기본 키 ────────────────────────────────────────────────────────────
    CONSTRAINT pk_idempotency_key
        PRIMARY KEY (idempotency_key),

    -- ── 유니크 (client_id + idempotency_key 조합) ─────────────────────────
    CONSTRAINT uq_idempotency_key_client_combo
        UNIQUE (client_id, idempotency_key),

    -- ── 멱등키상태 CHECK (3개) ────────────────────────────────────────────
    CONSTRAINT chk_idempotency_key_status
        CHECK (idempotency_status IN ('PROCESSING', 'COMPLETED', 'FAILED')),

    -- ── retry_count CHECK ────────────────────────────────────────────────
    CONSTRAINT chk_idempotency_key_retry_count
        CHECK (retry_count >= 0)
);


-- ── V1 payment_instruction → idempotency_key FK 마무리 ──────────────────────
-- (V1 작성 시점엔 idempotency_key 테이블 미존재. V2에서 테이블 생성 후 ALTER.)

ALTER TABLE payment_instruction
    ADD CONSTRAINT fk_payment_instruction_idempotency
    FOREIGN KEY (idempotency_key)
    REFERENCES idempotency_key (idempotency_key);


-- ── 테이블/컬럼 한글 코멘트 (13개) ──────────────────────────────────────────

COMMENT ON TABLE idempotency_key IS '멱등키';

COMMENT ON COLUMN idempotency_key.idempotency_key       IS '멱등키값';
COMMENT ON COLUMN idempotency_key.client_id             IS '클라이언트식별자';
COMMENT ON COLUMN idempotency_key.request_hash          IS '요청내용해시';
COMMENT ON COLUMN idempotency_key.idempotency_status    IS '멱등키상태';
COMMENT ON COLUMN idempotency_key.first_response_snap   IS '첫응답스냅샷';
COMMENT ON COLUMN idempotency_key.retry_count           IS '재시도횟수';
COMMENT ON COLUMN idempotency_key.first_received_at     IS '최초수신시각';
COMMENT ON COLUMN idempotency_key.last_received_at      IS '마지막수신시각';
COMMENT ON COLUMN idempotency_key.expires_at            IS '만료시각';
COMMENT ON COLUMN idempotency_key.first_registered_at   IS '최초등록일시';
COMMENT ON COLUMN idempotency_key.first_registrant_id   IS '최초등록자식별번호';
COMMENT ON COLUMN idempotency_key.last_modified_at      IS '최종수정일시';
COMMENT ON COLUMN idempotency_key.last_modifier_id      IS '최종수정자식별번호';
