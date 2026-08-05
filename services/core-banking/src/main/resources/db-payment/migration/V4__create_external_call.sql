-- =============================================================
-- V4__create_external_call.sql
-- 외부호출 테이블
-- PostgreSQL 16 / Flyway
-- ★ spec 비고:
--   - INBOUND_RESPONSE: v6 deprecated, 데이터 호환용으로 enum 유지
--   - BALANCE_WITHDRAW / BALANCE_DEPOSIT / LIMIT_CONSUME: spec 시트 12개 기준으로만 박음
-- =============================================================

CREATE TABLE external_call (

    -- ── 식별자 ──────────────────────────────────────────────────────────────
    call_id                             VARCHAR(20)     NOT NULL,
    call_idempotency_key                VARCHAR(150)    NOT NULL,

    -- ── 보상/재시도 계보 ────────────────────────────────────────────────────
    compensation_type                   VARCHAR(20)     NOT NULL        DEFAULT 'ORIGINAL',
    compensation_target_call_id         VARCHAR(20)     NULL,
    payment_instruction_id              VARCHAR(20)     NULL,
    parent_call_id                      VARCHAR(20)     NULL,

    -- ── 호출 컨텍스트 (외부 도메인 — FK 없음) ─────────────────────────────
    session_id                          VARCHAR(50)     NULL,
    user_id                             VARCHAR(20)     NULL,

    -- ── 호출 명세 ──────────────────────────────────────────────────────────
    call_type                           VARCHAR(30)     NOT NULL,
    target_system                       VARCHAR(50)     NOT NULL,
    endpoint_url                        VARCHAR(500)    NOT NULL,
    http_method                         VARCHAR(10)     NOT NULL,
    request_id                          VARCHAR(50)     NOT NULL,

    -- ── 요청 원본 박제 ─────────────────────────────────────────────────────
    request_header                      JSONB           NULL,
    request_body                        JSONB           NULL,
    request_body_hash                   VARCHAR(100)    NULL,

    -- ── 응답 원본 박제 ─────────────────────────────────────────────────────
    response_status_code                INT             NULL,
    response_header                     JSONB           NULL,
    response_body                       JSONB           NULL,
    business_response_code              VARCHAR(10)     NULL,
    response_message                    VARCHAR(500)    NULL,

    -- ── 결과/재시도 ────────────────────────────────────────────────────────
    result                              VARCHAR(20)     NOT NULL,
    attempt_no                          INT             NOT NULL        DEFAULT 1,

    -- ── 시각/성능 ──────────────────────────────────────────────────────────
    requested_at                        TIMESTAMP(3)    NOT NULL,
    responded_at                        TIMESTAMP(3)    NULL,
    response_time_ms                    INT             NULL,
    timeout_ms                          INT             NOT NULL,

    -- ── 등록/수정 (외부 도메인 직원 ID — FK 없음) ─────────────────────────
    first_registered_at                 TIMESTAMP(3)    NOT NULL        DEFAULT CURRENT_TIMESTAMP(3),
    first_registrant_id                 VARCHAR(20)     NULL,
    last_modified_at                    TIMESTAMP(3)    NOT NULL        DEFAULT CURRENT_TIMESTAMP(3),
    last_modifier_id                    VARCHAR(20)     NULL,


    -- ── 기본 키 ────────────────────────────────────────────────────────────
    CONSTRAINT pk_external_call
        PRIMARY KEY (call_id),

    -- ── 유니크 ─────────────────────────────────────────────────────────────
    CONSTRAINT uq_external_call_idempotency_key
        UNIQUE (call_idempotency_key),
    CONSTRAINT uq_external_call_request_id
        UNIQUE (request_id),

    -- ── FK: 결제지시 (NULL 허용 — 예금주조회는 결제지시 생성 전) ─────────
    CONSTRAINT fk_external_call_payment_instruction
        FOREIGN KEY (payment_instruction_id)
        REFERENCES payment_instruction (payment_instruction_id),

    -- ── FK: 보상대상 self (NULL 허용) ──────────────────────────────────────
    CONSTRAINT fk_external_call_compensation_target
        FOREIGN KEY (compensation_target_call_id)
        REFERENCES external_call (call_id),

    -- ── FK: 부모호출 self (NULL 허용) ──────────────────────────────────────
    CONSTRAINT fk_external_call_parent
        FOREIGN KEY (parent_call_id)
        REFERENCES external_call (call_id),

    -- ── 보상유형 CHECK (3개) ──────────────────────────────────────────────
    CONSTRAINT chk_external_call_compensation_type
        CHECK (compensation_type IN ('ORIGINAL', 'RETRY', 'COMPENSATION')),

    -- ── 호출종류 CHECK (12개) ─────────────────────────────────────────────
    CONSTRAINT chk_external_call_call_type
        CHECK (call_type IN (
            'ACCOUNT_OWNER_INQUIRY', 'BALANCE_INQUIRY', 'LIMIT_CHECK', 'AUTH_VERIFY',
            'FRAUD_CHECK', 'KFTC_GATEWAY', 'BOK_GATEWAY', 'INBOUND_RESPONSE',
            'BALANCE_WITHDRAW_CANCEL', 'BALANCE_DEPOSIT_CANCEL',
            'LIMIT_CONSUME_CANCEL', 'AUTH_REVOKE'
        )),

    -- ── HTTP메서드 CHECK (4개) ────────────────────────────────────────────
    CONSTRAINT chk_external_call_http_method
        CHECK (http_method IN ('GET', 'POST', 'PUT', 'DELETE')),

    -- ── 결과 CHECK (4개) ─────────────────────────────────────────────────
    CONSTRAINT chk_external_call_result
        CHECK (result IN ('SUCCESS', 'FAIL', 'TIMEOUT', 'NETWORK_ERROR')),

    -- ── 카운터 CHECK ─────────────────────────────────────────────────────
    CONSTRAINT chk_external_call_attempt_no
        CHECK (attempt_no >= 1),
    CONSTRAINT chk_external_call_response_time_ms
        CHECK (response_time_ms IS NULL OR response_time_ms >= 0),
    CONSTRAINT chk_external_call_timeout_ms
        CHECK (timeout_ms >= 0),

    -- ── 보상/재시도 일관성 CHECK ──────────────────────────────────────────
    CONSTRAINT chk_external_call_compensation_consistency
        CHECK (compensation_type <> 'COMPENSATION' OR compensation_target_call_id IS NOT NULL),
    CONSTRAINT chk_external_call_retry_consistency
        CHECK (compensation_type <> 'RETRY' OR parent_call_id IS NOT NULL)
);


-- ── 테이블/컬럼 한글 코멘트 (31개) ──────────────────────────────────────────

COMMENT ON TABLE external_call IS '외부호출';

COMMENT ON COLUMN external_call.call_id                         IS '외부호출번호';
COMMENT ON COLUMN external_call.call_idempotency_key            IS '호출멱등키';
COMMENT ON COLUMN external_call.compensation_type               IS '보상유형';
COMMENT ON COLUMN external_call.compensation_target_call_id     IS '보상대상호출번호';
COMMENT ON COLUMN external_call.payment_instruction_id          IS '결제지시번호';
COMMENT ON COLUMN external_call.parent_call_id                  IS '부모호출번호';
COMMENT ON COLUMN external_call.session_id                      IS '세션ID';
COMMENT ON COLUMN external_call.user_id                         IS '고객번호';
COMMENT ON COLUMN external_call.call_type                       IS '호출종류';
COMMENT ON COLUMN external_call.target_system                   IS '대상시스템';
COMMENT ON COLUMN external_call.endpoint_url                    IS '엔드포인트URL';
COMMENT ON COLUMN external_call.http_method                     IS 'HTTP메서드';
COMMENT ON COLUMN external_call.request_id                      IS '요청ID';
COMMENT ON COLUMN external_call.request_header                  IS '요청헤더';
COMMENT ON COLUMN external_call.request_body                    IS '요청본문';
COMMENT ON COLUMN external_call.request_body_hash               IS '요청본문해시';
COMMENT ON COLUMN external_call.response_status_code            IS '응답상태코드';
COMMENT ON COLUMN external_call.response_header                 IS '응답헤더';
COMMENT ON COLUMN external_call.response_body                   IS '응답본문';
COMMENT ON COLUMN external_call.business_response_code          IS '비즈니스응답코드';
COMMENT ON COLUMN external_call.response_message                IS '응답메시지';
COMMENT ON COLUMN external_call.result                          IS '결과';
COMMENT ON COLUMN external_call.attempt_no                      IS '시도번호';
COMMENT ON COLUMN external_call.requested_at                    IS '요청시각';
COMMENT ON COLUMN external_call.responded_at                    IS '응답시각';
COMMENT ON COLUMN external_call.response_time_ms                IS '응답시간_ms';
COMMENT ON COLUMN external_call.timeout_ms                      IS '타임아웃설정값';
COMMENT ON COLUMN external_call.first_registered_at             IS '최초등록일시';
COMMENT ON COLUMN external_call.first_registrant_id             IS '최초등록자식별번호';
COMMENT ON COLUMN external_call.last_modified_at                IS '최종수정일시';
COMMENT ON COLUMN external_call.last_modifier_id                IS '최종수정자식별번호';
