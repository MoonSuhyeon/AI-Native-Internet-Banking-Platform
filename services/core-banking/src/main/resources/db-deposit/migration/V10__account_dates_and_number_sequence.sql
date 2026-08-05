BEGIN;

CREATE SEQUENCE IF NOT EXISTS deposit_account_number_seq
    START WITH 100000000001
    INCREMENT BY 1
    NO CYCLE;

ALTER TABLE deposit_contracts
    ALTER COLUMN started_at TYPE DATE USING to_date(started_at, 'YYYYMMDD'),
    ALTER COLUMN maturity_at TYPE DATE USING CASE WHEN maturity_at IS NULL THEN NULL ELSE to_date(maturity_at, 'YYYYMMDD') END,
    ALTER COLUMN terminated_at TYPE DATE USING CASE WHEN terminated_at IS NULL THEN NULL ELSE to_date(terminated_at, 'YYYYMMDD') END,
    ALTER COLUMN status_changed_at TYPE DATE USING CASE WHEN status_changed_at IS NULL THEN NULL ELSE to_date(status_changed_at, 'YYYYMMDD') END;

ALTER TABLE deposit_accounts
    ALTER COLUMN opened_at TYPE DATE USING to_date(opened_at, 'YYYYMMDD'),
    ALTER COLUMN maturity_at TYPE DATE USING CASE WHEN maturity_at IS NULL THEN NULL ELSE to_date(maturity_at, 'YYYYMMDD') END,
    ALTER COLUMN dormant_at TYPE DATE USING CASE WHEN dormant_at IS NULL THEN NULL ELSE to_date(dormant_at, 'YYYYMMDD') END,
    ALTER COLUMN dormant_released_at TYPE DATE USING CASE WHEN dormant_released_at IS NULL THEN NULL ELSE to_date(dormant_released_at, 'YYYYMMDD') END,
    ALTER COLUMN closed_at TYPE DATE USING CASE WHEN closed_at IS NULL THEN NULL ELSE to_date(closed_at, 'YYYYMMDD') END,
    ALTER COLUMN status_changed_at TYPE DATE USING CASE WHEN status_changed_at IS NULL THEN NULL ELSE to_date(status_changed_at, 'YYYYMMDD') END;

COMMIT;
