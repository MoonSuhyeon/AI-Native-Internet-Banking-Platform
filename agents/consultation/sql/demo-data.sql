-- consultation-service 데모 데이터
-- DDL 실행 후 이 파일을 적용합니다.
-- deposit 관련 테이블도 동일 DB에 있다고 가정합니다 (deposit_db 공유 구조).

BEGIN;

-- ── 수신 상품 ──────────────────────────────────────────────────────────────

INSERT INTO deposit_banking_products (
    banking_product_id, deposit_product_name, deposit_product_type,
    description, base_interest_rate,
    min_join_amount, max_join_amount,
    min_period_month, max_period_month,
    is_early_termination_allowed, is_tax_benefit_available,
    deposit_product_status
)
SELECT * FROM (VALUES
    (1, '정기예금 플러스',     'DEPOSIT',      '안정적인 정기예금',        3.5, 100000, 100000000,   1,  60, true,  true,  'SELLING'),
    (2, '자유적금',           'SAVINGS',      '자유롭게 납입하는 적금',    4.0,  10000,  50000000,  12,  36, false, true,  'SELLING'),
    (3, '주택청약종합저축',    'SUBSCRIPTION', '청약 자격 적립 상품',       2.8,   2000,   1000000,   1, 600, false, false, 'SELLING')
) AS v(bid, pname, ptype, desc_, rate, min_a, max_a, min_p, max_p, early, tax, status)
WHERE NOT EXISTS (SELECT 1 FROM deposit_banking_products WHERE banking_product_id = v.bid);

-- ── 금리 ──────────────────────────────────────────────────────────────────

INSERT INTO banking_deposit_product_interest_rates (
    rate_id, banking_product_id, rate_type,
    minimum_contract_period, maximum_contract_period,
    interest_rate, condition_description
)
SELECT * FROM (VALUES
    (1, 1, 'BASE',         12, 24, 3.5, '기본금리'),
    (2, 1, 'PREFERENTIAL', 12, 24, 0.3, '급여이체 우대'),
    (3, 2, 'BASE',         12, 36, 4.0, '기본금리'),
    (4, 2, 'PREFERENTIAL', 24, 36, 0.5, '자동이체 우대'),
    (5, 3, 'BASE',         12, 600, 2.8, '기본금리')
) AS v(rid, bid, rtype, minp, maxp, rate, cond)
WHERE NOT EXISTS (SELECT 1 FROM banking_deposit_product_interest_rates WHERE rate_id = v.rid);

-- ── 약관 ──────────────────────────────────────────────────────────────────

INSERT INTO deposit_special_terms (
    special_term_id, special_term_name, special_term_content,
    special_term_summary, is_required, status
)
SELECT * FROM (VALUES
    (1, '개인정보 수집 이용 동의', '개인정보를 수집하고 이용합니다.',     '개인정보 동의 요약', true,  'ACTIVE'),
    (2, '중도해지 약관',          '중도해지 시 약정이율의 50%가 적용됩니다.', '중도해지 이율 안내', true,  'ACTIVE'),
    (3, '자동이체 동의',          '지정 계좌에서 자동이체를 실행합니다.', '자동이체 동의',     false, 'ACTIVE')
) AS v(sid, sname, scontent, ssummary, req, status)
WHERE NOT EXISTS (SELECT 1 FROM deposit_special_terms WHERE special_term_id = v.sid);

-- ── 계좌 ──────────────────────────────────────────────────────────────────

INSERT INTO deposit_accounts (
    account_id, account_number, customer_id, account_type,
    account_alias, balance, currency, account_status, opened_at, closed_at
)
SELECT * FROM (VALUES
    (1, '001-123-000001', 'CUST001', 'DEPOSIT',  '내 예금',  5000000, 'KRW', 'ACTIVE', '20260101', NULL),
    (2, '001-123-000002', 'CUST001', 'SAVINGS',  '내 적금',  1200000, 'KRW', 'ACTIVE', '20260301', NULL),
    (3, '001-123-000003', 'CUST002', 'DEPOSIT',  '예금계좌', 3000000, 'KRW', 'ACTIVE', '20250601', NULL)
) AS v(aid, anum, cid, atype, alias, bal, cur, status, opened, closed)
WHERE NOT EXISTS (SELECT 1 FROM deposit_accounts WHERE account_id = v.aid);

-- ── 계약 ──────────────────────────────────────────────────────────────────

INSERT INTO deposit_contracts (
    contract_id, contract_number, customer_id, banking_product_id,
    join_amount, contract_interest_rate, started_at, maturity_at, contract_status
)
SELECT * FROM (VALUES
    (1, 'CTR-001', 'CUST001', 1, 5000000, 3.5, '20260101', '20270101', 'ACTIVE'),
    (2, 'CTR-002', 'CUST001', 2, 1200000, 4.0, '20260301', '20270301', 'ACTIVE'),
    (3, 'CTR-003', 'CUST002', 1, 3000000, 3.5, '20250601', '20260601', 'MATURED')
) AS v(cid, cnum, custid, bid, jamt, rate, started, maturity, status)
WHERE NOT EXISTS (SELECT 1 FROM deposit_contracts WHERE contract_id = v.cid);

-- ── 이자 내역 ──────────────────────────────────────────────────────────────

INSERT INTO deposit_interest_history (
    interest_id, contract_id, account_id,
    applied_interest_rate, interest_amount, interest_after_tax_amount, paid_at
)
SELECT * FROM (VALUES
    (1, 1, 1, 3.5, 175000, 148050, '20261231'),
    (2, 2, 2, 4.0,  48000,  40608, '20261231'),
    (3, 3, 3, 3.5, 105000,  88830, '20260531')
) AS v(iid, cid, aid, rate, amt, tax_amt, paid)
WHERE NOT EXISTS (SELECT 1 FROM deposit_interest_history WHERE interest_id = v.iid);

-- ── 거래 내역 ──────────────────────────────────────────────────────────────

INSERT INTO deposit_transactions (
    transaction_id, transaction_number, account_id,
    transaction_type, transaction_status, amount, created_at
)
SELECT * FROM (VALUES
    (1, 'TX-001', 1, 'TRANSFER', 'COMPLETED',  10000, '2026-05-01'),
    (2, 'TX-002', 1, 'TRANSFER', 'COMPLETED',  50000, '2026-05-10'),
    (3, 'TX-003', 2, 'DEPOSIT',  'COMPLETED', 100000, '2026-05-15'),
    (4, 'TX-004', 1, 'TRANSFER', 'PENDING',    30000, '2026-05-21')
) AS v(tid, tnum, aid, ttype, tstatus, amt, created)
WHERE NOT EXISTS (SELECT 1 FROM deposit_transactions WHERE transaction_id = v.tid);

COMMIT;
