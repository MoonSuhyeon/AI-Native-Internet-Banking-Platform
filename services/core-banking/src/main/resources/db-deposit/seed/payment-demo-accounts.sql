-- =============================================================================
-- 이체 시연용 계좌
--
-- 병합 전에는 이 계좌들이 DepositBalanceClientMock 에 하드코딩돼 있었다.
-- Mock 은 스텁이면서 동시에 시나리오 픽스처 역할을 겸했고, 걷어내면 시나리오도
-- 함께 사라지므로 실제 계좌 + 실제 한도 설정으로 옮긴다.
--
-- 한도초과 시연이 성립하는 이유:
--   DepositV1Service.getLimit() 은 1회 한도(perTx)와 일 한도를 모두
--   deposit_accounts.daily_withdraw_limit 에서 읽는다. 100 으로 두면
--   1,000원 이상 이체에서 LIMIT_EXCEEDED 가 발화한다.
--
-- 재실행 안전: 모든 INSERT 가 ON CONFLICT DO NOTHING.
-- =============================================================================

-- 계약 (계좌 → 계약 → 상품 FK). 계약 id 3001~ 대역 사용
-- (1001~ 은 데모 고객, 2001~ 은 9001 홍길동이 쓴다)
INSERT INTO deposit_contracts (
    contract_id, contract_number, customer_id, banking_product_id,
    join_amount, contract_interest_rate, final_interest_rate,
    contract_period_month, started_at, join_channel, contract_status
) VALUES
    (3001, 'DEMO-PAY-SENDER',   '9101', 1, 0, 0.00, 0.00, 12, '20250101', 'WEB', 'ACTIVE'),
    (3002, 'DEMO-PAY-RECEIVER', '9102', 1, 0, 0.00, 0.00, 12, '20250101', 'WEB', 'ACTIVE'),
    (3003, 'DEMO-PAY-LIMITED',  '9103', 1, 0, 0.00, 0.00, 12, '20250101', 'WEB', 'ACTIVE'),
    (3004, 'DEMO-PAY-BOK',      '9104', 1, 0, 0.00, 0.00, 12, '20250101', 'WEB', 'ACTIVE')
ON CONFLICT (contract_id) DO NOTHING;

-- 계좌
-- account_password 는 BCrypt 해시여야 한다(엔티티가 형식을 검증한다). 시연용 더미.
INSERT INTO deposit_accounts (
    account_number, customer_id, contract_id, account_type, bank_code,
    account_alias, balance, currency, account_password,
    daily_withdraw_limit, atm_withdraw_limit,
    account_status, opened_at
) VALUES
    -- 송신: 20억. BOK 라우팅 기준(10억) 이상 단건도 통과한다.
    ('12345678901234', '9101', 3001, 'DEPOSIT', '004', '이몽룡 이체계좌',
     2000000000.00, 'KRW', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
     2000000000, 2000000000, 'ACTIVE', '20250101'),

    -- 수신: 자행이체 도착지. 타행이체 시연에서는 B은행 DB 의 같은 계좌가 수신처가 된다.
    ('12345678905678', '9102', 3002, 'DEPOSIT', '004', '성춘향 이체계좌',
     1000000.00, 'KRW', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
     2000000000, 2000000000, 'ACTIVE', '20250101'),

    -- 한도초과 시연: 잔액은 넉넉하지만 1회 한도가 100원이다.
    ('99990000000001', '9103', 3003, 'DEPOSIT', '004', '한도제한 시연계좌',
     5000000.00, 'KRW', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
     100, 100, 'ACTIVE', '20250101'),

    -- 거액이체(BOK) 시연 송신계좌.
    ('11011100000', '9104', 3004, 'DEPOSIT', '004', '거액이체 시연계좌',
     2000000000.00, 'KRW', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
     2000000000, 2000000000, 'ACTIVE', '20250101')
ON CONFLICT (account_number) DO NOTHING;
