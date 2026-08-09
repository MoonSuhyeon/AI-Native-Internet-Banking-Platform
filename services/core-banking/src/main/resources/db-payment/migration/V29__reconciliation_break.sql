-- =============================================================
-- V29__reconciliation_break.sql
-- 대사 불일치 기록
--
-- 왜 저장하는가. 대사는 "지금 어긋난 것" 을 찾는 일이지만, 정작 필요한 것은
-- 추이다. 매 영업일 결과를 남기지 않으면 "어제도 있었는가", "며칠째 미결인가",
-- "고치고 나서 줄었는가" 를 답할 수 없다. 화면에 띄우고 마는 대사는 그날 그 화면을
-- 본 사람에게만 존재한다.
--
-- 재실행 가능해야 한다. 같은 영업일을 다시 돌리는 일은 흔하고(장애 복구, 로직 수정),
-- 그때마다 같은 불일치가 중복 적재되면 건수 기반 지표가 부풀어 신뢰를 잃는다.
-- (영업일, 망, 결제지시, 유형) 을 유일키로 두어 재실행이 갱신이 되게 한다.
-- =============================================================

CREATE TABLE reconciliation_break (

    -- ── 식별자 ──────────────────────────────────────────────────────────────
    reconciliation_break_id     BIGSERIAL       NOT NULL,

    -- ── 대사 기준 ──────────────────────────────────────────────────────────
    business_date               VARCHAR(8)      NOT NULL,
    network                     VARCHAR(10)     NOT NULL,

    -- ── 대상 결제 (FK 없음 — 외부에만 있는 건도 기록해야 한다) ────────────
    --
    -- MISSING_INTERNAL 은 내부에 결제지시가 아예 없는 경우다. FK 를 걸면 가장
    -- 위험한 축의 불일치를 저장할 수 없게 되어, 대사가 그 유형만 조용히 버린다.
    payment_instruction_id      VARCHAR(20)     NOT NULL,

    -- ── 무엇이 어긋났는가 ──────────────────────────────────────────────────
    break_type                  VARCHAR(30)     NOT NULL,

    -- ── 양쪽 금액 (차액만으로는 조치를 정할 수 없다) ──────────────────────
    internal_amount             DECIMAL(15,0)   NOT NULL        DEFAULT 0,
    external_amount             DECIMAL(15,0)   NOT NULL        DEFAULT 0,
    external_status             VARCHAR(20)     NULL,

    -- ── 사람이 읽을 설명 (조사 시작점) ────────────────────────────────────
    detail                      VARCHAR(300)    NOT NULL,

    -- ── 조사 상태 ──────────────────────────────────────────────────────────
    --
    -- 대사만 있고 조치 상태가 없으면 같은 불일치를 매일 새로 발견하게 된다.
    -- 조사 중인 것과 새로 생긴 것이 구별되지 않으면 목록은 금방 무시된다.
    resolution_status           VARCHAR(20)     NOT NULL        DEFAULT 'OPEN',
    resolution_note             VARCHAR(300)    NULL,
    resolved_by                 VARCHAR(20)     NULL,
    resolved_at                 TIMESTAMP(3)    NULL,

    -- ── 감사 ───────────────────────────────────────────────────────────────
    first_detected_at           TIMESTAMP(3)    NOT NULL,
    last_detected_at            TIMESTAMP(3)    NOT NULL,

    CONSTRAINT pk_reconciliation_break
        PRIMARY KEY (reconciliation_break_id),

    -- 재실행이 중복 적재가 아니라 갱신이 되게 한다.
    CONSTRAINT uq_reconciliation_break
        UNIQUE (business_date, network, payment_instruction_id, break_type),

    CONSTRAINT chk_recon_break_type
        CHECK (break_type IN (
            'REVERSED_BUT_SETTLED',
            'MISSING_EXTERNAL',
            'MISSING_INTERNAL',
            'AMOUNT_MISMATCH',
            'STALE_PENDING'
        )),

    CONSTRAINT chk_recon_network
        CHECK (network IN ('KFTC', 'BOK')),

    CONSTRAINT chk_recon_resolution_status
        CHECK (resolution_status IN ('OPEN', 'INVESTIGATING', 'RESOLVED', 'ACCEPTED'))
);

-- 일별 조회가 기본 접근 경로다 (대사 결과 화면·지표 집계).
CREATE INDEX idx_recon_break_date
    ON reconciliation_break (business_date, network);

-- 미해결 건만 보는 조회. 쌓인 뒤에는 이쪽이 훨씬 자주 불린다.
CREATE INDEX idx_recon_break_open
    ON reconciliation_break (resolution_status, business_date)
    WHERE resolution_status IN ('OPEN', 'INVESTIGATING');

COMMENT ON TABLE  reconciliation_break                        IS '대사 불일치 — 내부 원장과 외부 청산 기록이 어긋난 지점';
COMMENT ON COLUMN reconciliation_break.business_date          IS '대사 기준 영업일 (yyyyMMdd)';
COMMENT ON COLUMN reconciliation_break.network                IS '청산망 (KFTC=타행이체, BOK=거액이체)';
COMMENT ON COLUMN reconciliation_break.break_type             IS '불일치 유형 — 선언 순서가 심각도';
COMMENT ON COLUMN reconciliation_break.internal_amount        IS '내부 원장 청산대기 순액 (역분개 차감 후)';
COMMENT ON COLUMN reconciliation_break.external_amount        IS '외부 청산 기록 금액';
COMMENT ON COLUMN reconciliation_break.resolution_status      IS '조사 상태 — 없으면 같은 건을 매일 새로 발견한다';
COMMENT ON COLUMN reconciliation_break.first_detected_at      IS '최초 발견 — 며칠째 미결인지 세는 기준';
COMMENT ON COLUMN reconciliation_break.last_detected_at       IS '마지막 발견 — 재실행마다 갱신';
