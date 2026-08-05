-- V3: 수신 상품 조회 성능을 위한 인덱스 추가
-- V2 시드 데이터 이후, V5 전체 ERD 전 단계

CREATE INDEX IF NOT EXISTS idx_deposit_products_status
    ON deposit_banking_products (deposit_product_status);

CREATE INDEX IF NOT EXISTS idx_deposit_contracts_customer
    ON deposit_contracts (customer_id);

CREATE INDEX IF NOT EXISTS idx_deposit_accounts_customer
    ON deposit_accounts (customer_id);

CREATE INDEX IF NOT EXISTS idx_deposit_transactions_account
    ON deposit_transactions (account_id, status);
