-- ============================================================
-- STAGE Kafka Outbox — advisory 도메인 이벤트 발행용 Outbox
-- ============================================================

CREATE TABLE advisory_kafka_outbox (
    id            BIGSERIAL    PRIMARY KEY,
    aggregate_id  VARCHAR(64)  NOT NULL,
    event_type    VARCHAR(64)  NOT NULL,
    topic         VARCHAR(128) NOT NULL,
    record_key    VARCHAR(128),
    payload_json  TEXT         NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);

CREATE INDEX idx_advisory_kafka_outbox_pending
    ON advisory_kafka_outbox (created_at ASC)
    WHERE status = 'PENDING';
