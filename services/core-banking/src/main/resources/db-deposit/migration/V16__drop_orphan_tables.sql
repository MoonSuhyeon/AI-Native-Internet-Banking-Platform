-- V5__full_erd_schema.sql 에서 실수로 포함된 미사용 테이블 정리
--
-- 1) 한글 payment 테이블 6개
--    - payment-service 소속 테이블이 deposit-db에 잘못 생성됨
--    - deposit-service Java 코드에서 단 한 곳도 참조하지 않음
--    - FK 의존 순서대로 DROP
--
-- 2) ERD 단수형 중복 테이블 2개
--    - deposit_account  → deposit_accounts 가 실제 사용 (Account.java @Table)
--    - deposit_contract → deposit_contracts 가 실제 사용 (Contract.java @Table)

-- ── 1. 한글 payment 테이블 (FK 의존 순서: 자식 → 부모) ───────────────────────

DROP TABLE IF EXISTS "원장";
DROP TABLE IF EXISTS "상태이력";
DROP TABLE IF EXISTS "금융결제원청산거래";
DROP TABLE IF EXISTS "한국은행결제거래";
DROP TABLE IF EXISTS "결제지시";
DROP TABLE IF EXISTS "인증토큰";

-- ── 2. ERD 단수형 중복 테이블 ────────────────────────────────────────────────

DROP TABLE IF EXISTS deposit_account;
DROP TABLE IF EXISTS deposit_contract;

-- ── 3 & 4. 홍길동(9001) 잔액 보정 및 거래 날짜 재조정은 LocalDataSeeder로 이전 ────
-- NOW() 기반 UPDATE는 Flyway 최초 실행 시각에 동결되어 시간 경과 후 90일 cutoff를
-- 벗어나므로, 매 부팅마다 실행되는 LocalDataSeeder.refreshHongKildongDemoData()에서 처리한다.
