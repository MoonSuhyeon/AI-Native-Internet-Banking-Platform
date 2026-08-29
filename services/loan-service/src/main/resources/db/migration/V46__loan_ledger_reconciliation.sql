-- =============================================================
-- V46__loan_ledger_reconciliation.sql
-- 여신 보조부 ↔ 원장 대사 결과
--
-- 근거: C1 ⑤
--
-- 왜 필요한가.
--   복식부기는 우리 장부 안에서 차변과 대변이 맞는지만 본다. 여신이 자기 보조부에
--   적은 숫자가 원장과 맞는지는 전혀 보장하지 않는다. 두 계통이 각자 산출한 값을
--   맞춰 보는 것이 그것을 발견하는 유일한 수단이다.
--
--   그래서 DailyAccountingSummary 를 지우지 않았다. 소비처가 없다는 것은 삭제
--   이유가 아니라 통제가 아직 안 만들어졌다는 뜻이었고, 그 통제가 이것이다.
--   한쪽을 다른 쪽에서 뽑아 오면 대사는 항등식이 되어 아무것도 검증하지 못한다.
--
-- 무엇을 남기는가.
--   판정만이 아니라 양쪽 값을 함께 남긴다. 불일치를 나중에 볼 때 "얼마가 어긋났나"
--   가 아니라 "어느 쪽이 무엇이라고 말했나" 를 알아야 원인을 좁힐 수 있다.
-- =============================================================

CREATE TABLE loan_ledger_reconciliation (

    recon_id                    BIGSERIAL,
    base_date                   VARCHAR(8)      NOT NULL,

    -- ── 여신 보조부가 주장한 값 ────────────────────────────────────────────
    subledger_disbursed_amount  BIGINT          NOT NULL,
    subledger_disbursed_count   INT             NOT NULL,
    subledger_repaid_amount     BIGINT          NOT NULL,
    subledger_repaid_count      INT             NOT NULL,

    -- ── 원장이 말한 값 (정본) ──────────────────────────────────────────────
    ledger_disbursed_amount     BIGINT          NOT NULL,
    ledger_disbursed_count      INT             NOT NULL,
    ledger_repaid_amount        BIGINT          NOT NULL,
    ledger_repaid_count         INT             NOT NULL,

    -- ── 판정 ───────────────────────────────────────────────────────────────
    result                      VARCHAR(20)     NOT NULL,
    break_detail                VARCHAR(500)    NULL,

    reconciled_at               TIMESTAMPTZ     NOT NULL    DEFAULT now(),

    CONSTRAINT pk_loan_ledger_reconciliation
        PRIMARY KEY (recon_id),
    -- 하루에 한 번이다. 재실행하면 갱신한다.
    CONSTRAINT uq_loan_ledger_reconciliation_date
        UNIQUE (base_date),
    CONSTRAINT chk_loan_ledger_reconciliation_result
        CHECK (result IN ('MATCH', 'MISMATCH')),
    -- 불일치인데 무엇이 어긋났는지 적지 않으면 기록으로서 쓸모가 없다.
    CONSTRAINT chk_loan_ledger_reconciliation_detail
        CHECK (result <> 'MISMATCH' OR break_detail IS NOT NULL)
);

COMMENT ON TABLE  loan_ledger_reconciliation        IS '여신 보조부 ↔ 원장 대사 결과';
COMMENT ON COLUMN loan_ledger_reconciliation.result IS 'MATCH / MISMATCH';
