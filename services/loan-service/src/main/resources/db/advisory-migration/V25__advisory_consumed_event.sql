-- ============================================================
-- Kafka Consumer 멱등성 보장용 처리 이력 테이블.
-- at-least-once 환경에서 동일 메시지가 재전달될 때 중복 처리 방지.
-- ============================================================

CREATE TABLE advisory_consumed_event (
    id           BIGSERIAL    PRIMARY KEY,
    topic        VARCHAR(128) NOT NULL,
    partition    INTEGER      NOT NULL,
    kafka_offset BIGINT       NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    aggregate_id VARCHAR(64)  NOT NULL,
    consumed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_advisory_consumed_event UNIQUE (topic, partition, kafka_offset)
);
