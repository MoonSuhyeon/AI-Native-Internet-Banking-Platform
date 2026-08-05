-- =============================================================
-- V19__add_publishing_status.sql
-- outbox_message.publish_status CHECK 제약에 'PUBLISHING' 추가
-- PostgreSQL 16 / Flyway
-- =============================================================
-- PUBLISHING: Outbox 워커 인스턴스가 행을 선점(claim)한 상태.
--   PENDING → PUBLISHING (claimPending) → SENT / FAILED
--   크래시로 PUBLISHING 고착 시 Stuck 복구 워커가 PENDING 재설정.
-- =============================================================

ALTER TABLE outbox_message
    DROP CONSTRAINT chk_outbox_message_publish_status;

ALTER TABLE outbox_message
    ADD CONSTRAINT chk_outbox_message_publish_status
        CHECK (publish_status IN ('PENDING', 'PUBLISHING', 'SENT', 'FAILED'));
