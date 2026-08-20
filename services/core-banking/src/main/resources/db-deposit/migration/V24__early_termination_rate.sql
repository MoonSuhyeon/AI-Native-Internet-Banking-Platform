-- 중도해지 이율 정책 — "해지상세조회" 가 보여 줄 근거.
--
-- 해지 화면에 "해지상세조회" 버튼이 있었지만 핸들러가 없었다. 눌러도 아무 일이
-- 없었으니 고객은 얼마를 손해 보고 해지하는지 모른 채 "해지" 를 눌러야 했다.
--
-- **왜 표로 두는가.** 중도해지 이율은 은행이 정하는 정책값이고 상품·경과기간에 따라
-- 달라진다. 코드에 숫자로 박으면 정책이 바뀔 때마다 배포해야 하고, 무엇보다 고객에게
-- 보여 준 이율의 근거가 어디에도 남지 않는다. 이율을 화면에 하드코딩해 둔 자리가
-- 이미 여럿 있어(OPEN_ITEMS 6-b) 같은 실수를 되풀이하지 않는다.
--
-- **여기 적힌 구간은 AXful Bank 의 정책이다.** 실제 은행마다 다르며, 이 값은 이
-- 데모 은행이 정한 것이다. 바꿀 때는 이 표만 고치면 된다.

CREATE TABLE IF NOT EXISTS early_termination_rate (
    rate_id            BIGSERIAL     PRIMARY KEY,
    -- 어떤 상품군에 적용되는가. NULL 이면 모든 상품의 기본값.
    product_type       VARCHAR(30),
    -- 경과 비율 구간 [from, to). 계약기간 대비 실제 보유기간의 비율(0.0~1.0).
    elapsed_ratio_from NUMERIC(4,3)  NOT NULL,
    elapsed_ratio_to   NUMERIC(4,3)  NOT NULL,
    -- 약정이율에 곱하는 비율. 0.20 이면 약정이율의 20% 만 준다.
    rate_multiplier    NUMERIC(4,3)  NOT NULL,
    -- 곱한 결과가 이 값보다 낮으면 이 값을 준다(연 %). 최저보장이율.
    min_rate           NUMERIC(5,2)  NOT NULL,
    description        VARCHAR(200)  NOT NULL
);

COMMENT ON TABLE early_termination_rate IS
    '중도해지 이율 정책. AXful Bank 가 정한 값이며 은행마다 다르다.';
COMMENT ON COLUMN early_termination_rate.rate_multiplier IS
    '약정이율에 곱하는 비율. 곱한 값이 min_rate 보다 낮으면 min_rate 를 적용한다.';

-- 구간이 겹치거나 비면 어떤 이율이 나올지 정해지지 않는다.
ALTER TABLE early_termination_rate
    DROP CONSTRAINT IF EXISTS ck_etr_ratio_range;
ALTER TABLE early_termination_rate
    ADD CONSTRAINT ck_etr_ratio_range
    CHECK (elapsed_ratio_from >= 0
           AND elapsed_ratio_to <= 1.000
           AND elapsed_ratio_from < elapsed_ratio_to);

-- 배수와 최저이율이 음수면 이자가 마이너스가 된다.
ALTER TABLE early_termination_rate
    DROP CONSTRAINT IF EXISTS ck_etr_non_negative;
ALTER TABLE early_termination_rate
    ADD CONSTRAINT ck_etr_non_negative
    CHECK (rate_multiplier >= 0 AND rate_multiplier <= 1.000 AND min_rate >= 0);

CREATE INDEX IF NOT EXISTS ix_etr_lookup
    ON early_termination_rate (product_type, elapsed_ratio_from);

-- AXful Bank 기본 정책. 보유기간이 길수록 약정이율에 가까워진다.
INSERT INTO early_termination_rate
    (product_type, elapsed_ratio_from, elapsed_ratio_to, rate_multiplier, min_rate, description)
SELECT * FROM (VALUES
    (NULL::VARCHAR, 0.000::NUMERIC, 0.250::NUMERIC, 0.100::NUMERIC, 0.10::NUMERIC, '계약기간의 1/4 미만 경과'),
    (NULL::VARCHAR, 0.250::NUMERIC, 0.500::NUMERIC, 0.200::NUMERIC, 0.15::NUMERIC, '계약기간의 1/4 이상 1/2 미만 경과'),
    (NULL::VARCHAR, 0.500::NUMERIC, 0.750::NUMERIC, 0.400::NUMERIC, 0.20::NUMERIC, '계약기간의 1/2 이상 3/4 미만 경과'),
    (NULL::VARCHAR, 0.750::NUMERIC, 0.900::NUMERIC, 0.600::NUMERIC, 0.25::NUMERIC, '계약기간의 3/4 이상 90% 미만 경과'),
    (NULL::VARCHAR, 0.900::NUMERIC, 1.000::NUMERIC, 0.800::NUMERIC, 0.30::NUMERIC, '계약기간의 90% 이상 경과')
) AS v
WHERE NOT EXISTS (SELECT 1 FROM early_termination_rate);
