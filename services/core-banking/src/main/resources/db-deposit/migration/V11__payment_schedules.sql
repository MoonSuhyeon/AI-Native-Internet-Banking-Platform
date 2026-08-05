BEGIN;

-- 납입 스케줄 테이블 (자동이체 + 수동 납입 지연 추적)
CREATE TABLE deposit_payment_schedules (
    schedule_id          BIGSERIAL       PRIMARY KEY,
    contract_id          BIGINT          NOT NULL,
    account_id           BIGINT          NOT NULL,
    payment_round        INT             NOT NULL,
    scheduled_date       DATE            NOT NULL,
    scheduled_amount     NUMERIC(18,2)   NOT NULL,
    is_auto_transfer     BOOLEAN         NOT NULL DEFAULT false,
    source_account_id    BIGINT,
    status               VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    paid_at              TIMESTAMPTZ(3),
    actual_amount        NUMERIC(18,2),
    transaction_id       BIGINT,
    failure_reason_code  VARCHAR(50),
    created_at           TIMESTAMPTZ(3)  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ(3),
    CONSTRAINT fk_payment_schedule_contract
        FOREIGN KEY (contract_id) REFERENCES deposit_contracts (contract_id),
    CONSTRAINT fk_payment_schedule_account
        FOREIGN KEY (account_id) REFERENCES deposit_accounts (account_id)
);

CREATE INDEX idx_payment_schedules_contract ON deposit_payment_schedules (contract_id);
CREATE INDEX idx_payment_schedules_scheduled_date ON deposit_payment_schedules (scheduled_date, status);

-- 연속 미납 횟수, 자동이체 출금 계좌 컬럼 추가
ALTER TABLE deposit_contracts
    ADD COLUMN consecutive_miss_count INT  NOT NULL DEFAULT 0,
    ADD COLUMN source_account_id      BIGINT;

COMMIT;
