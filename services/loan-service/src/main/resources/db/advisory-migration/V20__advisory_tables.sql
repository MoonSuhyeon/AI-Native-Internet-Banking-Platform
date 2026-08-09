-- ============================================================
-- STAGE 5.5 심사관 어드바이저리 (편향감지 · 재심사권유)
-- Notes:
--   - rule / report : 등록계 (7 감사 컬럼 + Soft Delete)
--   - signal / ack / snapshot : append-only (created_at/by 만)
--   - LOAN_REVIEW.rev_id FK 는 ON DELETE NO ACTION (cascade 금지)
--   - 코드 컬럼은 CODE_MASTER(master_db) 참조 — FK 미설정 (서비스 간 DB 분리)
-- ============================================================

CREATE TABLE review_advisory_rule (
    rule_id                BIGSERIAL     PRIMARY KEY,
    rule_cd                VARCHAR(50)   NOT NULL,
    rule_name              VARCHAR(200)  NOT NULL,
    advisory_type_cd       VARCHAR(50)   NOT NULL,
    rule_category_cd       VARCHAR(50)   NOT NULL,
    severity_cd            VARCHAR(50)   NOT NULL,
    rule_params            JSONB,
    rule_version           VARCHAR(50)   NOT NULL,
    active_yn              CHAR(1)       NOT NULL DEFAULT 'Y',
    effective_start_date   VARCHAR(8),
    effective_end_date     VARCHAR(8),
    rule_desc              VARCHAR(500),
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by             BIGINT        NOT NULL,
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by             BIGINT        NOT NULL,
    deleted_at             TIMESTAMPTZ,
    deleted_by             BIGINT,
    version                INT           NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_review_advisory_rule_cd
    ON review_advisory_rule (rule_cd)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_review_advisory_rule_active
    ON review_advisory_rule (active_yn, advisory_type_cd)
    WHERE deleted_at IS NULL;

CREATE TABLE review_advisory_report (
    advr_id              BIGSERIAL     PRIMARY KEY,
    rev_id               BIGINT        NOT NULL REFERENCES loan_review(rev_id) ON DELETE NO ACTION,
    rule_id              BIGINT        NOT NULL REFERENCES review_advisory_rule(rule_id) ON DELETE NO ACTION,
    advisory_type_cd     VARCHAR(50)   NOT NULL,
    severity_cd          VARCHAR(50)   NOT NULL,
    advr_status_cd       VARCHAR(50)   NOT NULL,
    advr_title           VARCHAR(200)  NOT NULL,
    advr_summary         TEXT,
    advr_payload         JSONB,
    target_reviewer_id   BIGINT,
    generated_at         TIMESTAMPTZ   NOT NULL,
    first_viewed_at      TIMESTAMPTZ,
    resolved_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by           BIGINT        NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by           BIGINT        NOT NULL,
    deleted_at           TIMESTAMPTZ,
    deleted_by           BIGINT,
    version              INT           NOT NULL DEFAULT 0
);
CREATE INDEX idx_review_advisory_report_rev
    ON review_advisory_report (rev_id);
CREATE INDEX idx_review_advisory_report_reviewer_status
    ON review_advisory_report (target_reviewer_id, advr_status_cd)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_review_advisory_report_unresolved_critical
    ON review_advisory_report (rev_id, severity_cd, advr_status_cd)
    WHERE deleted_at IS NULL;

CREATE TABLE review_advisory_signal (
    advs_id                BIGSERIAL     PRIMARY KEY,
    advr_id                BIGINT        NOT NULL REFERENCES review_advisory_report(advr_id) ON DELETE NO ACTION,
    signal_kind_cd         VARCHAR(50)   NOT NULL,
    signal_metric          VARCHAR(100)  NOT NULL,
    observed_value         DECIMAL(20,6),
    threshold_value        DECIMAL(20,6),
    peer_baseline_value    DECIMAL(20,6),
    sample_size            INT,
    signal_detail          JSONB,
    observed_window_start  VARCHAR(8),
    observed_window_end    VARCHAR(8),
    observed_at            TIMESTAMPTZ   NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by             BIGINT        NOT NULL
);
CREATE INDEX idx_review_advisory_signal_advr
    ON review_advisory_signal (advr_id);

CREATE TABLE review_advisory_ack (
    advk_id              BIGSERIAL     PRIMARY KEY,
    advr_id              BIGINT        NOT NULL REFERENCES review_advisory_report(advr_id) ON DELETE NO ACTION,
    ack_reviewer_id      BIGINT        NOT NULL,
    ack_response_cd      VARCHAR(50)   NOT NULL,
    decision_change_yn   CHAR(1)       NOT NULL DEFAULT 'N',
    ack_reason_cd        VARCHAR(50),
    ack_remark           VARCHAR(500),
    before_decision_cd   VARCHAR(50),
    after_decision_cd    VARCHAR(50),
    acked_at             TIMESTAMPTZ   NOT NULL,
    client_ip            VARCHAR(64),
    device               VARCHAR(200),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by           BIGINT        NOT NULL
);
CREATE INDEX idx_review_advisory_ack_advr
    ON review_advisory_ack (advr_id, acked_at);

CREATE TABLE reviewer_decision_snapshot (
    rds_id                      BIGSERIAL     PRIMARY KEY,
    reviewer_id                 BIGINT        NOT NULL,
    snapshot_date               VARCHAR(8)    NOT NULL,
    aggregation_window_cd       VARCHAR(50)   NOT NULL,
    cohort_dimension_cd         VARCHAR(50)   NOT NULL,
    cohort_value                VARCHAR(100)  NOT NULL,
    total_review_count          INT           NOT NULL DEFAULT 0,
    approve_count               INT           NOT NULL DEFAULT 0,
    reject_count                INT           NOT NULL DEFAULT 0,
    pending_count               INT           NOT NULL DEFAULT 0,
    approve_rate_bps            INT           NOT NULL DEFAULT 0,
    reject_rate_bps             INT           NOT NULL DEFAULT 0,
    peer_avg_reject_rate_bps    INT,
    deviation_sigma             DECIMAL(10,4),
    snapshotted_at              TIMESTAMPTZ   NOT NULL,
    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by                  BIGINT        NOT NULL
);
CREATE INDEX idx_reviewer_decision_snapshot_reviewer_date
    ON reviewer_decision_snapshot (reviewer_id, snapshot_date);
CREATE UNIQUE INDEX uk_reviewer_decision_snapshot_unit
    ON reviewer_decision_snapshot
       (reviewer_id, snapshot_date, aggregation_window_cd, cohort_dimension_cd, cohort_value);

-- ============================================================
-- 끝.
-- ============================================================
