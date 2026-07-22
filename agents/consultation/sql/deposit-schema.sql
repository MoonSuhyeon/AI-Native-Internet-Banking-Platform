-- consultation-service 가 직접 조회하는 deposit 관련 테이블 DDL.
-- 단독 실행(docker-compose) 시 deposit-service 없이도 동작하도록 여기서 생성합니다.
-- deposit-service 가 이미 동일 DB에 테이블을 만든 경우 IF NOT EXISTS 로 무시됩니다.

BEGIN;

CREATE TABLE IF NOT EXISTS deposit_banking_products (
    banking_product_id          BIGSERIAL PRIMARY KEY,
    deposit_product_name        TEXT NOT NULL,
    deposit_product_type        TEXT,
    description                 TEXT,
    base_interest_rate          NUMERIC,
    min_join_amount             NUMERIC,
    max_join_amount             NUMERIC,
    min_period_month            INTEGER,
    max_period_month            INTEGER,
    is_early_termination_allowed BOOLEAN,
    is_tax_benefit_available    BOOLEAN,
    deposit_product_status      TEXT
);

CREATE TABLE IF NOT EXISTS banking_deposit_product_interest_rates (
    rate_id                     BIGSERIAL PRIMARY KEY,
    banking_product_id          BIGINT REFERENCES deposit_banking_products(banking_product_id),
    rate_type                   TEXT,
    minimum_contract_period     INTEGER,
    maximum_contract_period     INTEGER,
    interest_rate               NUMERIC,
    condition_description       TEXT
);

CREATE TABLE IF NOT EXISTS deposit_special_terms (
    special_term_id             BIGSERIAL PRIMARY KEY,
    special_term_name           TEXT,
    special_term_content        TEXT,
    special_term_summary        TEXT,
    is_required                 BOOLEAN,
    status                      TEXT
);

CREATE TABLE IF NOT EXISTS deposit_accounts (
    account_id                  BIGSERIAL PRIMARY KEY,
    account_number              TEXT,
    customer_id                 TEXT,
    account_type                TEXT,
    account_alias               TEXT,
    balance                     NUMERIC,
    currency                    TEXT,
    account_status              TEXT,
    opened_at                   TEXT,
    closed_at                   TEXT
);

CREATE TABLE IF NOT EXISTS deposit_contracts (
    contract_id                 BIGSERIAL PRIMARY KEY,
    contract_number             TEXT,
    customer_id                 TEXT,
    banking_product_id          BIGINT REFERENCES deposit_banking_products(banking_product_id),
    join_amount                 NUMERIC,
    contract_interest_rate      NUMERIC,
    started_at                  TEXT,
    maturity_at                 TEXT,
    contract_status             TEXT
);

CREATE TABLE IF NOT EXISTS deposit_interest_history (
    interest_id                 BIGSERIAL PRIMARY KEY,
    contract_id                 BIGINT REFERENCES deposit_contracts(contract_id),
    account_id                  BIGINT REFERENCES deposit_accounts(account_id),
    applied_interest_rate       NUMERIC,
    interest_amount             NUMERIC,
    interest_after_tax_amount   NUMERIC,
    paid_at                     TEXT
);

CREATE TABLE IF NOT EXISTS deposit_transactions (
    transaction_id              BIGSERIAL PRIMARY KEY,
    transaction_number          TEXT,
    account_id                  BIGINT REFERENCES deposit_accounts(account_id),
    contract_id                 BIGINT REFERENCES deposit_contracts(contract_id),
    transaction_type            TEXT,
    transaction_status          TEXT,
    direction_type              VARCHAR(10) NOT NULL DEFAULT 'DEBIT',
    amount                      NUMERIC,
    balance_after               NUMERIC,
    memo                        TEXT,
    transaction_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMIT;
