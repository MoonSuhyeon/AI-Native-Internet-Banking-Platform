-- deposit_accounts에 사기 플래그 및 지급보류 금액 컬럼 추가
-- payment-service 연동 명세(A-1 fraudFlag, B-1 holdAmount) 지원

ALTER TABLE deposit_accounts
    ADD COLUMN IF NOT EXISTS fraud_flag     BOOLEAN        NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS hold_amount    NUMERIC(18, 2) NOT NULL DEFAULT 0.00;
