-- =============================================================================
-- 수신계 테이블에 낙관적 락(version) 을 채운다
--
-- **왜.** 같은 DB 안에서 동시성 보호가 갈려 있었다.
--   payment 스키마: payment_instruction 에 version 있음
--   deposit 스키마: deposit_accounts 하나만 있음(V9). 나머지 16개 테이블은 없음
--
-- 잔액은 비관적 락(SELECT ... FOR UPDATE)으로 막고 있지만, 계약·상품·한도·특약은
-- 무방비였다. 창구에서 계약을 수정하는 동안 만기 배치가 같은 행을 갱신하면 나중 쓰기가
-- 앞의 것을 조용히 덮는다. 덮였다는 사실조차 남지 않는 것이 이 결함의 성질이다.
--
-- 엔티티 쪽은 deposit.common.BaseEntity 에 @Version 을 두어 17개 엔티티가 한 번에
-- 받는다. 그래서 그 BaseEntity 를 상속하는 테이블 전부에 컬럼이 있어야 한다.
--
-- deposit_transactions 는 추가만 하고 수정하지 않으므로 낙관적 락이 실제로 쓰일 일은
-- 없다. 그래도 함께 넣는다 — 상속 구조상 컬럼이 없으면 매핑이 깨지고, 예외를 두면
-- "이 테이블만 왜 다른가"를 다음 사람이 다시 조사해야 한다.
--
-- 기존 행은 DEFAULT 0 으로 채워진다.
-- =============================================================================

ALTER TABLE banking_deposit_product_interest_rates    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE banking_deposit_product_special_terms     ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE banking_deposit_products                  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE deposit_banking_products                  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE deposit_contract_applied_rates            ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE deposit_contract_special_term_agreements  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE deposit_contracts                         ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE deposit_departments                       ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE deposit_interest_history                  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE deposit_payment_schedules                 ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE deposit_savings_products                  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE deposit_special_terms                     ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE deposit_subscription_products             ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE deposit_target_groups                     ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE deposit_term_application_management       ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE deposit_transactions                      ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- deposit_accounts 는 V9 에서 이미 추가됐다.
