-- =============================================================================
-- 수신계 금액 컬럼을 BIGINT(원 단위)로 통일한다
--
-- **정본은 데이터 사전이다.** docs/data_dictionary.md:
--     | 금액 | amount / amt | BIGINT 원 단위 |
--     | 잔액 | balance / bal | BIGINT       |
-- customer_ddl_design.md 도 annual_income_amount·capital_amount 를 BIGINT "원화 단위"로
-- 규정한다. loan 은 실제로 BIGINT 61개(자바 Long 67필드)로 이 규약을 지키고 있고
-- customer 도 같다. 어긋난 것은 수신계와 결제계 둘뿐이다.
--
--   deposit  : NUMERIC(18,2)  ← 이 마이그레이션이 고친다
--   payment  : DECIMAL(15,0)  ← V25 에서 함께 고친다
--   loan     : BIGINT
--   customer : BIGINT
--
-- **왜 소수부가 필요 없나.** 원화만 다룬다(외화 지원 계획 없음). 또 타입이 다르면 같은
-- 이체 금액이 두 스키마를 오갈 때 스케일이 달라져, BigDecimal 비교에서 100000 과
-- 100000.00 이 equals 로 다른 값이 된다(compareTo 로는 같다). 조용히 틀리는 종류다.
--
-- **소수 절사 책임은 애플리케이션에 남는다.** 이자 계산은 소수를 만든다(연이율 × 기간).
-- DB 가 반올림하면 "앱이 절사를 빠뜨렸다"는 사실이 가려지므로, 절사는 계산하는 쪽에서
-- 명시적으로 하고 DB 는 정수만 받는 자리로 둔다.
--
-- **금리는 건드리지 않는다.** NUMERIC(5,2)·NUMERIC(6,4) 는 이율·세율이라 소수가 본질이다.
-- 데이터 사전도 금리는 bps 단위 INT, 금액만 BIGINT 로 구분한다.
--
-- 대상 32개 컬럼 / 11개 테이블. NUMERIC(18,2) 전부다.
-- hold_amount 는 V17 이 ALTER TABLE ADD COLUMN 으로 넣어 CREATE TABLE 본문에 없다 —
-- 컬럼 목록을 CREATE TABLE 만 훑어 만들면 빠지므로 주의.
--
-- 데이터가 없는 지금 적용한다. 소수부가 있는 행이 쌓인 뒤에는 절사·반올림·거부 중
-- 무엇을 할지 따로 정해야 한다.
--
-- V5 가 만든 deposit_account·deposit_contract(단수)는 V16 에서 지워졌으므로 대상이 아니다.
-- =============================================================================

ALTER TABLE deposit_accounts
    ALTER COLUMN balance                TYPE BIGINT,
    ALTER COLUMN daily_withdraw_limit   TYPE BIGINT,
    ALTER COLUMN atm_withdraw_limit     TYPE BIGINT,
    ALTER COLUMN total_paid_amount      TYPE BIGINT,
    ALTER COLUMN total_interest_amount  TYPE BIGINT,
    ALTER COLUMN hold_amount            TYPE BIGINT;

ALTER TABLE deposit_accounts
    ALTER COLUMN hold_amount SET DEFAULT 0;

ALTER TABLE deposit_transactions
    ALTER COLUMN amount                   TYPE BIGINT,
    ALTER COLUMN fee_amount               TYPE BIGINT,
    ALTER COLUMN balance_before           TYPE BIGINT,
    ALTER COLUMN balance_after            TYPE BIGINT,
    ALTER COLUMN available_balance_after  TYPE BIGINT;

ALTER TABLE deposit_contracts
    ALTER COLUMN join_amount               TYPE BIGINT,
    ALTER COLUMN expected_interest_amount  TYPE BIGINT;

ALTER TABLE deposit_interest_history
    ALTER COLUMN interest_amount           TYPE BIGINT,
    ALTER COLUMN interest_before_tax       TYPE BIGINT,
    ALTER COLUMN interest_tax_amount       TYPE BIGINT,
    ALTER COLUMN local_income_tax_amount   TYPE BIGINT,
    ALTER COLUMN interest_after_tax        TYPE BIGINT;

ALTER TABLE deposit_banking_products
    ALTER COLUMN min_join_amount TYPE BIGINT,
    ALTER COLUMN max_join_amount TYPE BIGINT;

ALTER TABLE banking_deposit_product_interest_rates
    ALTER COLUMN minimum_join_amount TYPE BIGINT,
    ALTER COLUMN maximum_join_amount TYPE BIGINT;

ALTER TABLE deposit_savings_products
    ALTER COLUMN monthly_payment_min_amount TYPE BIGINT,
    ALTER COLUMN monthly_payment_max_amount TYPE BIGINT;

ALTER TABLE deposit_subscription_products
    ALTER COLUMN monthly_payment_amount         TYPE BIGINT,
    ALTER COLUMN min_monthly_payment            TYPE BIGINT,
    ALTER COLUMN max_monthly_payment            TYPE BIGINT,
    ALTER COLUMN max_recognized_payment_amount  TYPE BIGINT;

ALTER TABLE deposit_subscription_payment_recognition_history
    ALTER COLUMN payment_amount    TYPE BIGINT,
    ALTER COLUMN recognized_amount TYPE BIGINT;

ALTER TABLE deposit_payment_schedules
    ALTER COLUMN scheduled_amount TYPE BIGINT,
    ALTER COLUMN actual_amount    TYPE BIGINT;
