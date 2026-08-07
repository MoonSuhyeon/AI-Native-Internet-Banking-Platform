-- =============================================================================
-- 결제계 금액 컬럼을 BIGINT(원 단위)로 통일한다
--
-- **정본은 데이터 사전이다.** docs/data_dictionary.md:
--     | 금액 | amount / amt | BIGINT 원 단위 |
--     | 잔액 | balance / bal | BIGINT       |
--
-- loan(BIGINT 61개)·customer(BIGINT)가 이 규약을 지키고 있고 어긋난 것은 수신계와
-- 결제계뿐이었다. 수신계는 V20 에서 함께 고친다.
--
-- DECIMAL(15,0) 은 이미 정수라 값은 그대로다. 바뀌는 것은 표현 방식이다.
--   - 자바가 BigDecimal 이 아니라 Long 을 받는다 → loan·customer 와 같은 타입이 된다
--   - BigDecimal 의 스케일 함정(100000 vs 100000.00 이 equals 로 다름)이 사라진다
--   - 원 단위 정수라는 사실이 타입에 드러난다
--
-- **금액이 아닌 컬럼은 없다.** 결제계의 NUMERIC/DECIMAL 은 이 7개가 전부이고 모두 금액이다
-- (금리·비율 컬럼이 없다).
--
-- 값 범위: BIGINT 는 922경까지 담는다. DECIMAL(15,0) 의 999조보다 넓으므로 절삭 위험은 없다.
-- =============================================================================

ALTER TABLE payment_instruction
    ALTER COLUMN transfer_amount TYPE BIGINT,
    ALTER COLUMN fee_amount      TYPE BIGINT;

ALTER TABLE ledger
    ALTER COLUMN amount         TYPE BIGINT,
    ALTER COLUMN balance_before TYPE BIGINT,
    ALTER COLUMN balance_after  TYPE BIGINT;

ALTER TABLE kftc_clearing_transaction
    ALTER COLUMN clearing_amount TYPE BIGINT;

ALTER TABLE bok_settlement_transaction
    ALTER COLUMN settlement_amount TYPE BIGINT;
