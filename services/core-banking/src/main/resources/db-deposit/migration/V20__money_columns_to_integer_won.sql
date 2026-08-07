-- =============================================================================
-- 수신계 금액 컬럼을 원화 정수(NUMERIC(15,0))로 통일한다
--
-- **왜.** 한 DB 안에서 금액 표현이 갈려 있었다.
--   deposit 스키마: NUMERIC(18,2) — 소수 둘째 자리까지
--   payment 스키마: DECIMAL(15,0) — 정수
--
-- 원화만 다루므로(외화 지원 계획 없음) 소수부는 쓰이지 않는다. 그런데 타입이 다르면
-- 같은 이체 금액이 두 스키마를 오갈 때 스케일이 달라지고, BigDecimal 비교에서
-- 100000 과 100000.00 이 equals 로는 다른 값이 된다(compareTo 로는 같다).
-- 이 차이는 조용히 틀리는 종류라 타입을 맞춘다.
--
-- **소수 절사 책임은 애플리케이션에 남는다.** 이자 계산은 소수를 만든다
-- (연이율 × 기간). DB 가 반올림하면 "앱이 절사를 빠뜨렸다"는 사실이 가려지므로,
-- 절사는 계산하는 쪽에서 명시적으로 하고 DB 는 정수만 받는 자리로 둔다.
-- 지금 코드도 RoundingMode 를 명시해 계산한다(CashflowBasedRecommendService).
--
-- 데이터가 없는 지금 적용한다. 값이 쌓인 뒤에는 소수부가 있는 행을 어떻게 처리할지
-- (절사·반올림·거부) 따로 정해야 한다.
--
-- V5 가 만든 deposit_account·deposit_contract(단수)는 V16 에서 지워졌으므로 대상이 아니다.
-- =============================================================================

ALTER TABLE deposit_accounts
    ALTER COLUMN balance                TYPE NUMERIC(15,0),
    ALTER COLUMN daily_withdraw_limit   TYPE NUMERIC(15,0),
    ALTER COLUMN atm_withdraw_limit     TYPE NUMERIC(15,0),
    ALTER COLUMN total_paid_amount      TYPE NUMERIC(15,0),
    ALTER COLUMN total_interest_amount  TYPE NUMERIC(15,0);

ALTER TABLE deposit_transactions
    ALTER COLUMN amount                   TYPE NUMERIC(15,0),
    ALTER COLUMN fee_amount               TYPE NUMERIC(15,0),
    ALTER COLUMN balance_before           TYPE NUMERIC(15,0),
    ALTER COLUMN balance_after            TYPE NUMERIC(15,0),
    ALTER COLUMN available_balance_after  TYPE NUMERIC(15,0);

ALTER TABLE deposit_contracts
    ALTER COLUMN join_amount               TYPE NUMERIC(15,0),
    ALTER COLUMN expected_interest_amount  TYPE NUMERIC(15,0);

ALTER TABLE deposit_interest_history
    ALTER COLUMN interest_amount           TYPE NUMERIC(15,0),
    ALTER COLUMN interest_before_tax       TYPE NUMERIC(15,0),
    ALTER COLUMN interest_tax_amount       TYPE NUMERIC(15,0),
    ALTER COLUMN local_income_tax_amount   TYPE NUMERIC(15,0),
    ALTER COLUMN interest_after_tax        TYPE NUMERIC(15,0);

ALTER TABLE deposit_banking_products
    ALTER COLUMN min_join_amount TYPE NUMERIC(15,0),
    ALTER COLUMN max_join_amount TYPE NUMERIC(15,0);

ALTER TABLE banking_deposit_product_interest_rates
    ALTER COLUMN minimum_join_amount TYPE NUMERIC(15,0),
    ALTER COLUMN maximum_join_amount TYPE NUMERIC(15,0);

ALTER TABLE deposit_savings_products
    ALTER COLUMN monthly_payment_min_amount TYPE NUMERIC(15,0),
    ALTER COLUMN monthly_payment_max_amount TYPE NUMERIC(15,0);

ALTER TABLE deposit_subscription_products
    ALTER COLUMN monthly_payment_amount         TYPE NUMERIC(15,0),
    ALTER COLUMN min_monthly_payment            TYPE NUMERIC(15,0),
    ALTER COLUMN max_monthly_payment            TYPE NUMERIC(15,0),
    ALTER COLUMN max_recognized_payment_amount  TYPE NUMERIC(15,0);

ALTER TABLE deposit_subscription_payment_recognition_history
    ALTER COLUMN payment_amount    TYPE NUMERIC(15,0),
    ALTER COLUMN recognized_amount TYPE NUMERIC(15,0);

ALTER TABLE deposit_payment_schedules
    ALTER COLUMN scheduled_amount TYPE NUMERIC(15,0),
    ALTER COLUMN actual_amount    TYPE NUMERIC(15,0);
