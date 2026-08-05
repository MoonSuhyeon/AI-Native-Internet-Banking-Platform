-- V4: 상품 금리 및 계약 제약조건 추가
-- V3 인덱스 이후, V5 전체 ERD 전 단계

ALTER TABLE deposit_banking_products
    ADD COLUMN IF NOT EXISTS max_interest_rate NUMERIC(5,2),
    ADD COLUMN IF NOT EXISTS promotion_end_date DATE;

ALTER TABLE banking_deposit_product_interest_rates
    ADD COLUMN IF NOT EXISTS effective_date DATE,
    ADD COLUMN IF NOT EXISTS expiry_date    DATE;
