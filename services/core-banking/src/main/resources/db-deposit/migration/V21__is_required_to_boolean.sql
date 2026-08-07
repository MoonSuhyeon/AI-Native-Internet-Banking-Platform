-- =============================================================================
-- deposit_term_application_management.is_required 를 BOOLEAN 으로
--
-- **왜 이 컬럼 하나인가.** deposit 스키마의 CHAR(1) 은 42개처럼 보였지만 그중 41개는
-- V5__full_erd_schema 가 만든 교차도메인 유령 테이블의 것이고, 그 테이블들은 V16·V18 에서
-- 이미 지워졌다. 살아 있는 CHAR(1) 은 이 컬럼 하나다.
--
-- 그래서 이건 규모 문제가 아니라 일관성 문제다. deposit 의 다른 is_* 컬럼은 전부 BOOLEAN 이다.
--   is_early_termination_allowed BOOLEAN
--   is_tax_benefit_available     BOOLEAN
--   is_required                  CHAR(1)  ← 혼자 'Y'/'N'
-- payment 스키마도 BOOLEAN 으로 통일돼 있다(is_intra_bank, is_scheduled).
--
-- is_ 로 시작하는 이름은 불린을 뜻한다. 이름과 타입이 어긋나면 읽는 쪽에서
-- "Y"·"true"·1 중 무엇과 비교해야 하는지 매번 확인해야 한다.
--
-- customer·loan 의 *_yn CHAR(1) 은 건드리지 않는다. 그쪽은 이름부터 _yn 이라 값이 Y/N 임이
-- 드러나고, 두 도메인이 일관되게 쓰고 있다. 규약이 다른 것과 규약이 깨진 것은 다르다.
-- =============================================================================

ALTER TABLE deposit_term_application_management
    ALTER COLUMN is_required DROP DEFAULT;

ALTER TABLE deposit_term_application_management
    ALTER COLUMN is_required TYPE BOOLEAN
        USING (CASE WHEN upper(is_required) IN ('Y', 'T', 'TRUE', '1') THEN TRUE ELSE FALSE END);

ALTER TABLE deposit_term_application_management
    ALTER COLUMN is_required SET DEFAULT FALSE;
