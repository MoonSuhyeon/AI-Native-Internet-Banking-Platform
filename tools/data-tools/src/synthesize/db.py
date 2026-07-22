"""PostgreSQL 적재 — ai_db 의 synthetic_application 테이블에 합성 결과 bulk insert."""

from __future__ import annotations

import io
import logging
import os
from pathlib import Path

import pandas as pd
import psycopg2

log = logging.getLogger(__name__)

DDL = """
CREATE TABLE IF NOT EXISTS synthetic_application (
    uuid                            text PRIMARY KEY,
    -- Layer 1 persona
    sex                             text,
    age                             smallint,
    marital_status                  text,
    military_status                 text,
    family_type                     text,
    housing_type                    text,
    education_level                 text,
    bachelors_field                 text,
    occupation                      text,
    district                        text,
    province                        text,
    country                         text,
    persona                         text,
    applicant_segment               text,
    sample_weight                   double precision,
    -- Layer 2 financial
    income_quintile                 smallint,
    annual_income_kw                bigint,
    total_asset_kw                  bigint,
    total_debt_kw                   bigint,
    collateral_debt_kw              bigint,
    credit_debt_kw                  bigint,
    dsr                             double precision,
    ltv                             double precision,
    monthly_cashflow_mean_kw        bigint,
    monthly_cashflow_std_kw        bigint,
    delinquency_history_24m         smallint,
    credit_score_proxy              integer,
    -- Layer 3 application
    product_code                    text,
    requested_amount_kw             bigint,
    requested_period_mo             smallint,
    purpose_cd                      text,
    purpose_text                    text,
    purpose_red_flag                boolean,
    -- Layer 4 oracle
    oracle_score                    smallint,
    oracle_decision                 text,
    oracle_suggested_amount_kw      bigint,
    oracle_suggested_rate_bps       integer,
    oracle_noise_flipped            boolean,
    oracle_bias_injected            boolean,
    -- meta
    split                           text,
    generated_at                    timestamptz NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_syn_app_decision ON synthetic_application(oracle_decision);
CREATE INDEX IF NOT EXISTS idx_syn_app_segment  ON synthetic_application(applicant_segment);
CREATE INDEX IF NOT EXISTS idx_syn_app_split    ON synthetic_application(split);
"""


def _conn():
    return psycopg2.connect(
        host=os.getenv("AI_DB_HOST", "localhost"),
        port=int(os.getenv("AI_DB_PORT", "5437")),
        dbname=os.getenv("AI_DB_NAME", "ai_db"),
        user=os.getenv("AI_DB_USER", "ai"),
        password=os.getenv("AI_DB_PASSWORD", "ai"),
    )


def ensure_schema():
    with _conn() as c, c.cursor() as cur:
        cur.execute(DDL)


def bulk_load(df: pd.DataFrame, truncate: bool = True) -> int:
    """COPY 방식 대량 적재. truncate 시 기존 데이터 제거."""
    ensure_schema()
    columns = list(df.columns)
    buf = io.StringIO()
    df.to_csv(buf, index=False, header=False, na_rep="\\N", sep="\t")
    buf.seek(0)
    with _conn() as c, c.cursor() as cur:
        if truncate:
            cur.execute("TRUNCATE TABLE synthetic_application")
        cur.copy_from(buf, "synthetic_application", sep="\t", null="\\N", columns=columns)
        c.commit()
    log.info("loaded %d rows into synthetic_application", len(df))
    return len(df)


def save_parquet(df: pd.DataFrame, version: str = "v1") -> Path:
    from loaders.config import PROJECT_ROOT
    out_dir = PROJECT_ROOT / "data" / "synthetic" / "applications"
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"synthetic_application_{version}.parquet"
    df.to_parquet(out, compression="zstd", index=False)
    log.info("saved parquet %s (%d rows, %d KB)", out, len(df), out.stat().st_size // 1024)
    return out
