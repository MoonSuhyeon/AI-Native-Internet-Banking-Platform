ALTER TABLE deposit_target_groups
    ADD COLUMN IF NOT EXISTS min_age INTEGER,
    ADD COLUMN IF NOT EXISTS max_age INTEGER;

-- 청년고객: 만 19~34세
UPDATE deposit_target_groups SET min_age = 19, max_age = 34 WHERE target_group_name = '청년고객';

-- 국군장병: 만 18~27세 (현역 복무 연령 기준)
UPDATE deposit_target_groups SET min_age = 18, max_age = 27 WHERE target_group_name = '국군장병';
