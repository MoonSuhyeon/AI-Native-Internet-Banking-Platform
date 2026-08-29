-- =============================================================
-- V29__seed_loan_system_accounts.sql
-- 여신 집행·수납 계좌를 deposit 에 실존화한다
--
-- 근거: docs/plan/loan-payment-ledger-trace.md 차단 4
--
-- 여신은 결제를 부를 때 송신계좌로 common_db 의 시스템 계좌 번호를 넘긴다
-- (SystemAccountProvider — LOAN_DISBURSEMENT / LOAN_COLLECTION). 그런데 결제는
-- 송신계좌를 deposit 에서 찾는다(PaymentOrchestratorImpl → getAccountByNo).
-- 그 계좌가 deposit 에 없어서, 다른 차단을 모두 풀어도 여기서 멈춘다.
--
-- 확인한 것: deposit_accounts 37건 중 '0040000000001'·'0040000000002' 0건.
--
-- 왜 내부계정(KB-LOAN-CR 같은 문자열)으로 하지 않는가.
--   원장의 KB-CLR-088·KB-FEE-001 은 분개의 상대편으로만 쓰이고 잔액을 갖지 않는다.
--   반면 집행·수납 계좌는 실제로 자금이 드나드는 곳이라 잔액·한도 검사를 지나야 한다.
--   지금 결제 경로는 송신계좌가 deposit 실계좌임을 전제하므로 실계좌로 만든다.
--
--   회계적 상대계정(대출채권·이자수익)은 이것과 다른 축이고, 원장을 완결하는
--   C1 ④ 에서 내부계정으로 만든다. 둘을 섞지 않는다.
--
-- 은행 자기 계좌를 고객 예금계약으로 표현하는 왜곡이 남는다. deposit_accounts
-- .contract_id 가 NOT NULL + FK 라 계약 행이 필요하기 때문이다. 이 왜곡을 푸는 것은
-- 계정과목 체계를 세우는 일이라 C1 ④ 범위다 — 여기서는 경로를 여는 데 그친다.
-- =============================================================


-- ── 상품 ─────────────────────────────────────────────────────────────────────
--
-- 자기 상품을 만든다. 데모 시드의 상품(id=1)을 참조하면 시드가 없는 환경 —
-- 마이그레이션만 도는 새 DB, 즉 테스트 컨테이너 — 에서 FK 위반으로 기동이 죽는다.
-- 실제로 통합 테스트 7개가 그렇게 넘어졌다.
--
-- 마이그레이션은 시드에 기대면 안 된다. 시드는 환경마다 있고 없다.
--
-- 대상 테이블 이름에 주의한다. deposit 스키마에는 이름이 비슷한 둘이 있고
-- deposit_contracts 의 FK 가 가리키는 것은 deposit_banking_products 다.
--
--   deposit_banking_products   상품 마스터. PK = banking_product_id  ← 이쪽
--   banking_deposit_products   예금 상세. PK = deposit_product_id
--
-- 처음에 뒤엣것에 넣어 ON CONFLICT 가 맞는 제약을 못 찾고 실패했다.

INSERT INTO deposit_banking_products (banking_product_id, deposit_product_type, deposit_product_name)
VALUES (9001, 'DEPOSIT', '여신 시스템계좌용')
ON CONFLICT (banking_product_id) DO NOTHING;


-- ── 계약 ─────────────────────────────────────────────────────────────────────
-- 계약 id 5301~ 대역. 데모 고객이 1001~5203 을 쓴다.

INSERT INTO deposit_contracts (
    contract_id, contract_number, customer_id, banking_product_id,
    join_amount, contract_interest_rate, final_interest_rate,
    contract_period_month, started_at, join_channel, contract_status
) VALUES
    (5301, 'SYS-LOAN-DISBURSEMENT', 'SYSTEM', 9001, 0, 0.00, 0.00, 12, '20250101', 'BRANCH', 'ACTIVE'),
    (5302, 'SYS-LOAN-COLLECTION',   'SYSTEM', 9001, 0, 0.00, 0.00, 12, '20250101', 'BRANCH', 'ACTIVE')
ON CONFLICT (contract_id) DO NOTHING;


-- ── 계좌 ─────────────────────────────────────────────────────────────────────
--
-- 집행계좌에 잔액을 심는다. 대출 실행 자금이 여기서 나가는데 0 이면 첫 실행이
-- 잔액부족으로 실패한다. 실제 은행에서 이 계좌를 채우는 것은 자금부의 일이고,
-- 데모에서는 그에 해당하는 것이 이 시드다.
--
-- 수납계좌는 0 에서 시작한다. 상환이 들어오는 쪽이라 미리 채울 이유가 없다.
--
-- account_password 는 BCrypt 해시여야 한다(엔티티가 형식을 검증한다). 시스템
-- 계좌는 비밀번호로 접근하는 계좌가 아니지만 컬럼이 NOT NULL 이라 더미를 넣는다.
--
-- 한도를 크게 두는 이유는 여신 한도가 이미 service_permission.max_amount 에서
-- 걸리기 때문이다. 여기서 또 막으면 거절 사유가 두 곳으로 갈려 감사에서 읽기 어렵다.

INSERT INTO deposit_accounts (
    account_number, customer_id, contract_id, account_type, bank_code,
    account_alias, balance, currency, account_password,
    daily_withdraw_limit, atm_withdraw_limit,
    account_status, opened_at
) VALUES
    ('0040000000002', 'SYSTEM', 5301, 'DEPOSIT', '004', '여신 집행계좌',
     10000000000, 'KRW', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
     10000000000, 10000000000, 'ACTIVE', '20250101'),

    ('0040000000001', 'SYSTEM', 5302, 'DEPOSIT', '004', '여신 수납계좌',
     0, 'KRW', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
     10000000000, 10000000000, 'ACTIVE', '20250101')
ON CONFLICT (account_number) DO NOTHING;
