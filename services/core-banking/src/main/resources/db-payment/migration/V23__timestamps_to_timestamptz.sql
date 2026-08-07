-- =============================================================================
-- 결제계 시각 컬럼을 TIMESTAMPTZ 로 통일한다 (34개 컬럼 / 8개 테이블)
--
-- **왜.** 같은 DB 안에서 시각 표현이 갈려 있었다.
--   deposit 스키마: TIMESTAMPTZ  (124개)
--   payment 스키마: TIMESTAMP(3) (타임존 없음, 34개)
--
-- 이체 한 건이 deposit_transactions.transaction_at(TZ 있음)과
-- payment_instruction.requested_at(TZ 없음)에 각각 남는다. 두 값을 비교하거나 조인하면
-- 서버 타임존에 따라 결과가 달라진다. 은행에서 거래 시각은 분쟁의 근거이고 마감
-- 판정(business_date)도 여기에 걸리므로, 절대 시각으로 통일한다.
--
-- **왜 UTC 로 해석하는가.** 기존 값은 Java 의 LocalDateTime.now() 가 만든 벽시계 시각이고,
-- 컨테이너 JVM 기본 타임존이 UTC 다(Dockerfile·compose 에 TZ 설정 없음). 즉 저장된
-- 값은 UTC 벽시계이므로 UTC 로 읽어야 원래 순간이 보존된다.
-- 이 전제를 못 박기 위해 compose 에 TZ=UTC 를 명시했다 — 그게 바뀌면 이 변환도 틀어진다.
--
-- 지금 적용하는 이유: 아직 배포 전이라 데이터가 없다. 운영 데이터가 쌓인 뒤에는 같은
-- 변환이 "어느 타임존의 벽시계였나"를 되짚는 작업이 된다.
-- =============================================================================

ALTER TABLE payment_instruction
    ALTER COLUMN holder_inquiry_at       TYPE TIMESTAMPTZ(3) USING holder_inquiry_at       AT TIME ZONE 'UTC',
    ALTER COLUMN requested_at            TYPE TIMESTAMPTZ(3) USING requested_at            AT TIME ZONE 'UTC',
    ALTER COLUMN completed_at            TYPE TIMESTAMPTZ(3) USING completed_at            AT TIME ZONE 'UTC',
    ALTER COLUMN next_retry_at           TYPE TIMESTAMPTZ(3) USING next_retry_at           AT TIME ZONE 'UTC',
    ALTER COLUMN next_timeout_at         TYPE TIMESTAMPTZ(3) USING next_timeout_at         AT TIME ZONE 'UTC',
    ALTER COLUMN scheduled_execution_at  TYPE TIMESTAMPTZ(3) USING scheduled_execution_at  AT TIME ZONE 'UTC',
    ALTER COLUMN first_registered_at     TYPE TIMESTAMPTZ(3) USING first_registered_at     AT TIME ZONE 'UTC',
    ALTER COLUMN last_modified_at        TYPE TIMESTAMPTZ(3) USING last_modified_at        AT TIME ZONE 'UTC';

ALTER TABLE idempotency_key
    ALTER COLUMN first_received_at       TYPE TIMESTAMPTZ(3) USING first_received_at       AT TIME ZONE 'UTC',
    ALTER COLUMN last_received_at        TYPE TIMESTAMPTZ(3) USING last_received_at        AT TIME ZONE 'UTC',
    ALTER COLUMN expires_at              TYPE TIMESTAMPTZ(3) USING expires_at              AT TIME ZONE 'UTC',
    ALTER COLUMN first_registered_at     TYPE TIMESTAMPTZ(3) USING first_registered_at     AT TIME ZONE 'UTC',
    ALTER COLUMN last_modified_at        TYPE TIMESTAMPTZ(3) USING last_modified_at        AT TIME ZONE 'UTC';

ALTER TABLE ledger
    ALTER COLUMN posted_at               TYPE TIMESTAMPTZ(3) USING posted_at               AT TIME ZONE 'UTC',
    ALTER COLUMN first_registered_at     TYPE TIMESTAMPTZ(3) USING first_registered_at     AT TIME ZONE 'UTC',
    ALTER COLUMN last_modified_at        TYPE TIMESTAMPTZ(3) USING last_modified_at        AT TIME ZONE 'UTC';

ALTER TABLE external_call
    ALTER COLUMN requested_at            TYPE TIMESTAMPTZ(3) USING requested_at            AT TIME ZONE 'UTC',
    ALTER COLUMN responded_at            TYPE TIMESTAMPTZ(3) USING responded_at            AT TIME ZONE 'UTC',
    ALTER COLUMN first_registered_at     TYPE TIMESTAMPTZ(3) USING first_registered_at     AT TIME ZONE 'UTC',
    ALTER COLUMN last_modified_at        TYPE TIMESTAMPTZ(3) USING last_modified_at        AT TIME ZONE 'UTC';

ALTER TABLE outbox_message
    ALTER COLUMN available_at            TYPE TIMESTAMPTZ(3) USING available_at            AT TIME ZONE 'UTC',
    ALTER COLUMN published_at            TYPE TIMESTAMPTZ(3) USING published_at            AT TIME ZONE 'UTC',
    ALTER COLUMN first_registered_at     TYPE TIMESTAMPTZ(3) USING first_registered_at     AT TIME ZONE 'UTC',
    ALTER COLUMN last_modified_at        TYPE TIMESTAMPTZ(3) USING last_modified_at        AT TIME ZONE 'UTC';

ALTER TABLE status_history
    ALTER COLUMN event_occurred_at       TYPE TIMESTAMPTZ(3) USING event_occurred_at       AT TIME ZONE 'UTC',
    ALTER COLUMN db_recorded_at          TYPE TIMESTAMPTZ(3) USING db_recorded_at          AT TIME ZONE 'UTC',
    ALTER COLUMN first_registered_at     TYPE TIMESTAMPTZ(3) USING first_registered_at     AT TIME ZONE 'UTC',
    ALTER COLUMN last_modified_at        TYPE TIMESTAMPTZ(3) USING last_modified_at        AT TIME ZONE 'UTC';

ALTER TABLE kftc_clearing_transaction
    ALTER COLUMN last_inquiry_at         TYPE TIMESTAMPTZ(3) USING last_inquiry_at         AT TIME ZONE 'UTC',
    ALTER COLUMN first_registered_at     TYPE TIMESTAMPTZ(3) USING first_registered_at     AT TIME ZONE 'UTC',
    ALTER COLUMN last_modified_at        TYPE TIMESTAMPTZ(3) USING last_modified_at        AT TIME ZONE 'UTC';

ALTER TABLE bok_settlement_transaction
    ALTER COLUMN last_inquiry_at         TYPE TIMESTAMPTZ(3) USING last_inquiry_at         AT TIME ZONE 'UTC',
    ALTER COLUMN first_registered_at     TYPE TIMESTAMPTZ(3) USING first_registered_at     AT TIME ZONE 'UTC',
    ALTER COLUMN last_modified_at        TYPE TIMESTAMPTZ(3) USING last_modified_at        AT TIME ZONE 'UTC';

-- DEFAULT 도 함께 옮긴다. CURRENT_TIMESTAMP(3) 는 timestamptz 를 돌려주므로 타입 변경 후에도
-- 유효하지만, 명시해 두어야 나중에 읽는 사람이 기본값의 타입을 의심하지 않는다.
ALTER TABLE payment_instruction
    ALTER COLUMN first_registered_at SET DEFAULT CURRENT_TIMESTAMP(3),
    ALTER COLUMN last_modified_at    SET DEFAULT CURRENT_TIMESTAMP(3);
