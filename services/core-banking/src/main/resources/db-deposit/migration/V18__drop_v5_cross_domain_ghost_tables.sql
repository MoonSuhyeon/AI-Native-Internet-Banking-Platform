-- V18__drop_v5_cross_domain_ghost_tables.sql
--
-- V5__full_erd_schema.sql 이 "전사 공통 ERD" 전체를 deposit-db 에 생성했으나,
-- deposit-service 코드는 V1 세대 스키마(deposit_*, banking_deposit_*)만 사용한다.
-- V16 에서 payment 소속 한글 테이블 6개만 정리됐고, 나머지 타 도메인 테이블은 남아 있었다.
--
-- 미사용 근거 (전수 확인):
--   - JPA @Table 매핑 20개는 전부 V1 세대 (deposit_* / banking_deposit_*)
--   - 아래 21개 테이블은 @Table 매핑 0건
--   - MyBatis XML · 네이티브 쿼리 · application.yml 참조 0건
--
-- 도메인 소유권 원칙: 각 도메인 테이블은 소유 서비스의 DB 에만 존재한다.
--   customer/party  → customer-service
--   loan_*          → loan-service
--   common_*        → 미구현 (통합 모델 설계만 있고 코드 이관 없음)
--
-- FK 의존 순서를 개별 추적하지 않고 CASCADE 로 일괄 제거한다.

-- ── 1. 여신 도메인 (loan-service 소속) ──────────────────────────────────────

DROP TABLE IF EXISTS loan_repayment    CASCADE;
DROP TABLE IF EXISTS loan_execution    CASCADE;
DROP TABLE IF EXISTS loan_review       CASCADE;
DROP TABLE IF EXISTS loan_contract     CASCADE;
DROP TABLE IF EXISTS loan_account      CASCADE;
DROP TABLE IF EXISTS loan_application  CASCADE;
DROP TABLE IF EXISTS loan_product      CASCADE;

-- ── 2. 고객·당사자 도메인 (customer-service 소속) ───────────────────────────

DROP TABLE IF EXISTS party_relation      CASCADE;
DROP TABLE IF EXISTS party_role          CASCADE;
DROP TABLE IF EXISTS party_organization  CASCADE;
DROP TABLE IF EXISTS party_person        CASCADE;
DROP TABLE IF EXISTS customer            CASCADE;
DROP TABLE IF EXISTS party               CASCADE;

-- ── 3. 미구현 전사 공통 모델 ─────────────────────────────────────────────────
--    실제 수신 거래·계좌·계약은 deposit_transactions / deposit_accounts /
--    deposit_contracts 를 사용한다.

DROP TABLE IF EXISTS common_transaction     CASCADE;
DROP TABLE IF EXISTS common_account         CASCADE;
DROP TABLE IF EXISTS common_contract        CASCADE;
DROP TABLE IF EXISTS terms_target_map       CASCADE;
DROP TABLE IF EXISTS common_terms_consent   CASCADE;
DROP TABLE IF EXISTS common_terms_template  CASCADE;
DROP TABLE IF EXISTS common_product         CASCADE;

-- ── 4. 수신 구세대 단수형 상품 테이블 ───────────────────────────────────────
--    실사용은 deposit_banking_products / banking_deposit_products /
--    deposit_savings_products / deposit_subscription_products.

DROP TABLE IF EXISTS deposit_product CASCADE;
