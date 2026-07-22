-- psi_baseline 테이블: 훈련 시점 분포 기준
CREATE TABLE psi_baseline (
    id             BIGSERIAL    PRIMARY KEY,
    feature_name   VARCHAR(128) NOT NULL,
    bucket_index   SMALLINT     NOT NULL,
    bucket_low     NUMERIC(18,6),
    bucket_high    NUMERIC(18,6),
    baseline_ratio NUMERIC(8,6) NOT NULL,
    baseline_date  DATE         NOT NULL,
    model_version  VARCHAR(64)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_psi_baseline UNIQUE (feature_name, bucket_index, model_version)
);

-- psi_drift_result 테이블: 주간 PSI 계산 결과
CREATE TABLE psi_drift_result (
    id            BIGSERIAL    PRIMARY KEY,
    feature_name  VARCHAR(128) NOT NULL,
    calc_week     DATE         NOT NULL,
    psi_value     NUMERIC(8,6) NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    sample_count  INT          NOT NULL,
    model_version VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_psi_status CHECK (status IN ('STABLE','WARNING','CRITICAL')),
    CONSTRAINT uq_psi_week    UNIQUE (feature_name, calc_week, model_version)
);
CREATE INDEX idx_pdr_feature_week ON psi_drift_result(feature_name, calc_week DESC);

-- fairness_report 테이블: 월별 집단별 승인률 (rate_gap은 app에서 계산, H2 호환)
CREATE TABLE fairness_report (
    id            BIGSERIAL    PRIMARY KEY,
    report_month  DATE         NOT NULL,
    group_key     VARCHAR(64)  NOT NULL,
    approval_rate NUMERIC(5,4) NOT NULL,
    sample_count  INT          NOT NULL,
    overall_rate  NUMERIC(5,4) NOT NULL,
    rate_gap      NUMERIC(5,4) NOT NULL,
    flagged       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_fairness UNIQUE (report_month, group_key)
);
CREATE INDEX idx_fr_month   ON fairness_report(report_month DESC);
