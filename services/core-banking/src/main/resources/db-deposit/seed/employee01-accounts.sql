-- =============================================================================
-- V13: 홍길동(customer_id=9001) 테스트 계좌 시드
-- 예금 1건(AXful 정기예금) + 적금 1건(AXful 내맘대로적금)
-- DB 재시작 후에도 항상 복구됨
-- =============================================================================

-- 계약 시퀀스 충돌 방지: 2001번부터 사용
INSERT INTO deposit_contracts (
    contract_id, contract_number, customer_id, banking_product_id,
    is_monthly_payment, join_amount,
    contract_interest_rate, total_preferential_rate, final_interest_rate,
    tax_benefit_type, applied_tax_rate, expected_interest_amount,
    contract_period_month, started_at, maturity_at,
    contract_status, join_channel, is_auto_renewal, auto_transfer_enabled,
    is_proxy_joined, is_power_of_attorney_verified, consecutive_miss_count
) VALUES
-- 정기예금 12개월
(2001, 'DEMO-EMP1-DEP-001', '9001', 1,
 false, 5000000.00,
 2.15, 0.00, 2.15,
 'GENERAL', 15.40, 107500.00,
 12, '2026-01-09', '2027-01-09',
 'ACTIVE', 'WEB', false, false,
 false, false, 0),
-- 적금 12개월
(2002, 'DEMO-EMP1-SAV-001', '9001', 5,
 true, 300000.00,
 2.95, 0.00, 2.95,
 'GENERAL', 15.40, 35400.00,
 12, '2026-01-09', '2027-01-09',
 'ACTIVE', 'WEB', false, true,
 false, false, 0)
ON CONFLICT (contract_id) DO NOTHING;

-- 계좌 시퀀스 충돌 방지: 2001번부터 사용
-- account_password: bcrypt("123456") = $2a$10$VKbbcNGoISwIX.xrAERLe.ehKBatPhD6qhq1VePuHOHwDh0SBukje
INSERT INTO deposit_accounts (
    account_id, account_number, customer_id, contract_id,
    account_type, saving_type, bank_code,
    balance, total_paid_amount, total_interest_amount,
    currency, account_password,
    is_withdrawable, is_online_banking_enabled, is_mobile_banking_enabled, is_phone_banking_enabled,
    account_status, opened_at, maturity_at, version
) VALUES
-- 정기예금 계좌
(2001, '001-901-000001', '9001', 2001,
 'DEPOSIT', NULL, '001',
 5000000.00, 5000000.00, 0.00,
 'KRW', '$2a$10$VKbbcNGoISwIX.xrAERLe.ehKBatPhD6qhq1VePuHOHwDh0SBukje',
 true, false, false, false,
 'ACTIVE', '2026-01-09', '2027-01-09', 0),
-- 적금 계좌
(2002, '001-901-000002', '9001', 2002,
 'SAVINGS', 'REGULAR', '001',
 1500000.00, 1500000.00, 0.00,
 'KRW', '$2a$10$VKbbcNGoISwIX.xrAERLe.ehKBatPhD6qhq1VePuHOHwDh0SBukje',
 true, false, false, false,
 'ACTIVE', '2026-01-09', '2027-01-09', 0)
ON CONFLICT (account_id) DO NOTHING;

-- 시퀀스를 2003 이상으로 조정 (새 가입이 충돌나지 않도록)
SELECT setval('deposit_contracts_contract_id_seq', GREATEST(2002, (SELECT MAX(contract_id) FROM deposit_contracts)));
SELECT setval('deposit_accounts_account_id_seq',   GREATEST(2002, (SELECT MAX(account_id)  FROM deposit_accounts)));

-- 최근 3개월 거래 시드 (현금흐름 추천 채점용)
-- 월 급여 약 330만원 입금, 지출 약 166만원 → 월 잉여자금 약 164만원
INSERT INTO deposit_transactions (
    transaction_number, account_id, transaction_type, direction_type,
    amount, balance_before, balance_after, available_balance_after,
    fee_amount, currency, status, channel_type,
    transaction_memo, transaction_summary, transaction_at
) VALUES
-- 3개월 전
('TX-9001-M3-IN-01',  2001, 'DEPOSIT',  'IN',  3300000, 5000000, 8300000, 8300000, 0, 'KRW', 'SUCCESS', 'WEB', '급여', '급여입금', NOW() - INTERVAL '89 days'),
('TX-9001-M3-OUT-01', 2001, 'WITHDRAW', 'OUT', 1660000, 8300000, 6640000, 6640000, 0, 'KRW', 'SUCCESS', 'WEB', '생활비', '생활비출금', NOW() - INTERVAL '75 days'),
-- 2개월 전
('TX-9001-M2-IN-01',  2001, 'DEPOSIT',  'IN',  3300000, 6640000, 9940000, 9940000, 0, 'KRW', 'SUCCESS', 'WEB', '급여', '급여입금', NOW() - INTERVAL '59 days'),
('TX-9001-M2-OUT-01', 2001, 'WITHDRAW', 'OUT', 1660000, 9940000, 8280000, 8280000, 0, 'KRW', 'SUCCESS', 'WEB', '생활비', '생활비출금', NOW() - INTERVAL '45 days'),
-- 1개월 전
('TX-9001-M1-IN-01',  2001, 'DEPOSIT',  'IN',  3300000, 8280000, 11580000, 11580000, 0, 'KRW', 'SUCCESS', 'WEB', '급여', '급여입금', NOW() - INTERVAL '29 days'),
('TX-9001-M1-OUT-01', 2001, 'WITHDRAW', 'OUT', 1660000, 11580000, 9920000, 9920000, 0, 'KRW', 'SUCCESS', 'WEB', '생활비', '생활비출금', NOW() - INTERVAL '15 days')
ON CONFLICT (transaction_number) DO NOTHING;
