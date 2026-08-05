-- V9: deposit_accounts 테이블에 낙관적 락 version 컬럼 추가
-- JPA @Version 필드와 매핑 — 동시 잔액 변경 시 충돌 감지용

ALTER TABLE deposit_accounts
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
