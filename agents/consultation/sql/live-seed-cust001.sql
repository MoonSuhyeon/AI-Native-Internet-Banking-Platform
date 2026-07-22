-- CUST001 데모 데이터 — 실제 deposit_db 스키마 기준
-- 기존 9001~9002 데이터와 충돌 없이 9101~ ID 사용

BEGIN;

-- ── 약관 ──────────────────────────────────────────────────────────────────────
INSERT INTO deposit_special_terms (
    special_term_name, special_term_content, special_term_summary,
    is_required, is_electronic_agreement_allowed, special_term_version,
    started_at, status
)
SELECT * FROM (VALUES
    ('개인정보 수집 이용 동의', '개인정보를 수집하고 이용합니다.',       '개인정보 동의 요약', TRUE,  TRUE, '1.0', '20260101', 'ACTIVE'),
    ('중도해지 약관',           '중도해지 시 약정이율의 50%가 적용됩니다.', '중도해지 이율 안내', TRUE,  TRUE, '1.0', '20260101', 'ACTIVE')
) AS v(nm, ct, sm, req, elec, ver, st, stat)
WHERE NOT EXISTS (SELECT 1 FROM deposit_special_terms WHERE special_term_name = v.nm);

-- ── CUST001 계약 ──────────────────────────────────────────────────────────────
INSERT INTO deposit_contracts (
    contract_id, contract_number, customer_id, banking_product_id,
    join_amount, contract_interest_rate, total_preferential_rate, final_interest_rate,
    tax_benefit_type, applied_tax_rate, contract_period_month,
    started_at, maturity_at, contract_status, join_channel
)
SELECT * FROM (VALUES
    (9101, 'CTR-CUST001-001', 'CUST001', 9001::BIGINT, 5000000.00, 3.20, 0.30, 3.50, 'GENERAL', 15.40, 12, '20260101', '20270101', 'ACTIVE',  'WEB'),
    (9102, 'CTR-CUST001-002', 'CUST001', 9002::BIGINT, 1200000.00, 3.60, 0.00, 3.60, 'GENERAL', 15.40, 24, '20250101', '20260101', 'MATURED', 'MOBILE')
) AS v(cid, cnum, custid, bid, jamt, rate, pref, final, tax_type, tax_rate, period, st, mt, status, ch)
WHERE NOT EXISTS (SELECT 1 FROM deposit_contracts WHERE contract_id = v.cid);

-- ── CUST001 계좌 ──────────────────────────────────────────────────────────────
INSERT INTO deposit_accounts (
    account_id, account_number, customer_id, contract_id,
    account_type, account_alias, balance, total_paid_amount, total_interest_amount,
    account_password, is_withdrawable, is_online_banking_enabled, is_mobile_banking_enabled,
    account_status, opened_at, maturity_at
)
SELECT * FROM (VALUES
    (9101, 'ACC-CUST001-001', 'CUST001', 9101::BIGINT, 'DEPOSIT', '내 예금',  5000000.00, 5000000.00, 0.00, '1234', TRUE, TRUE, TRUE, 'ACTIVE', '20260101', '20270101'),
    (9102, 'ACC-CUST001-002', 'CUST001', 9102::BIGINT, 'SAVINGS', '내 적금',  1200000.00, 1200000.00, 0.00, '1234', TRUE, TRUE, TRUE, 'ACTIVE', '20250101', '20260101')
) AS v(aid, anum, custid, cid, atype, alias, bal, paid, interest, pwd, wd, ob, mb, status, opened, maturity)
WHERE NOT EXISTS (SELECT 1 FROM deposit_accounts WHERE account_id = v.aid);

-- ── CUST001 이자 내역 ──────────────────────────────────────────────────────────
INSERT INTO deposit_interest_history (
    interest_id, contract_id, account_id,
    applied_interest_rate, interest_amount,
    tax_benefit_type, applied_tax_rate,
    interest_before_tax, interest_tax_amount, local_income_tax_amount, interest_after_tax,
    interest_reason, interest_paid_at
)
SELECT * FROM (VALUES
    (9101, 9101::BIGINT, 9101::BIGINT, 3.50, 175000.00, 'GENERAL', 15.4000, 175000.00, 24500.00, 2450.00, 148050.00, 'REGULAR_INTEREST', NOW()),
    (9102, 9102::BIGINT, 9102::BIGINT, 3.60,  48000.00, 'GENERAL', 15.4000,  48000.00,  6720.00,  672.00,  40608.00, 'REGULAR_INTEREST', NOW())
) AS v(iid, cid, aid, rate, amt, tax_type, tax_rate, before_tax, tax_amt, local_tax, after_tax, reason, paid_at)
WHERE NOT EXISTS (SELECT 1 FROM deposit_interest_history WHERE interest_id = v.iid);

-- ── CUST001 거래 내역 ──────────────────────────────────────────────────────────
INSERT INTO deposit_transactions (
    transaction_id, transaction_number, account_id,
    transaction_type, direction_type,
    amount, balance_before, balance_after,
    channel_type, transaction_at
)
SELECT * FROM (VALUES
    (9101, 'TX-CUST001-001', 9101::BIGINT, 'DEPOSIT',  'IN',  5000000.00,       0.00, 5000000.00, 'WEB',    NOW() - INTERVAL '30 days'),
    (9102, 'TX-CUST001-002', 9101::BIGINT, 'TRANSFER', 'OUT',   10000.00, 5000000.00, 4990000.00, 'MOBILE', NOW() - INTERVAL '15 days'),
    (9103, 'TX-CUST001-003', 9101::BIGINT, 'TRANSFER', 'OUT',   50000.00, 4990000.00, 4940000.00, 'WEB',    NOW() - INTERVAL '5 days')
) AS v(tid, tnum, aid, ttype, dir, amt, before_b, after_b, ch, tat)
WHERE NOT EXISTS (SELECT 1 FROM deposit_transactions WHERE transaction_id = v.tid);

-- ── 시퀀스 동기화 ─────────────────────────────────────────────────────────────
SELECT setval(pg_get_serial_sequence('deposit_contracts',         'contract_id'),    COALESCE((SELECT MAX(contract_id)    FROM deposit_contracts),    1), TRUE);
SELECT setval(pg_get_serial_sequence('deposit_accounts',          'account_id'),     COALESCE((SELECT MAX(account_id)     FROM deposit_accounts),     1), TRUE);
SELECT setval(pg_get_serial_sequence('deposit_interest_history',  'interest_id'),    COALESCE((SELECT MAX(interest_id)    FROM deposit_interest_history), 1), TRUE);
SELECT setval(pg_get_serial_sequence('deposit_transactions',      'transaction_id'), COALESCE((SELECT MAX(transaction_id) FROM deposit_transactions), 1), TRUE);
SELECT setval(pg_get_serial_sequence('deposit_special_terms',     'special_term_id'),COALESCE((SELECT MAX(special_term_id) FROM deposit_special_terms), 1), TRUE);

COMMIT;
