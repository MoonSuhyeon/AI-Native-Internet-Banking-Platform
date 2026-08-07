-- =============================================================================
-- 서류 에이전트의 시각 컬럼을 TIMESTAMPTZ 로 통일한다 (5개 컬럼 / 3개 테이블)
--
-- **왜.** 같은 스키마 안에서 갈려 있었다.
--   V1·V2 : TIMESTAMP   (타임존 없음) — loan_document_submission, loan_forgery_signal,
--                                       identity_verify_cache
--   V4     : TIMESTAMPTZ                — status_history
--
-- 나중에 추가된 status_history 만 절대시각을 쓰고 나머지는 아니었다. 한 서류의 제출 시각과
-- 상태 변경 시각을 나란히 놓고 보면 서버 타임존에 따라 순서가 뒤집힐 수 있다. 위조 의심
-- 서류의 탐지 시각(detected_at)은 심사 근거로 남는 값이라 절대시각이어야 한다.
--
-- 레포 전체로도 TIMESTAMPTZ 가 정본이다 — customer 165개, loan 158개, deposit 124개,
-- payment 는 V23 에서 전환했다. 남아 있던 TIMESTAMP 는 여기 5개와 여신계의 Spring Batch
-- 메타데이터 테이블(공식 스키마라 건드리면 JobRepository 가 깨진다) 뿐이었다.
--
-- **왜 UTC 로 해석하는가.** 두 경로 모두 UTC 다.
--   - 자바가 쓴 값: LocalDateTime.now() 가 만든 벽시계 시각이고 컨테이너 JVM 기본
--     타임존이 UTC 다(compose 의 doc-agent 에 TZ 설정 없음 → 베이스 이미지 기본값).
--   - DEFAULT now(): now() 는 timestamptz 를 돌려주는데 timestamp 컬럼에 넣으면 세션
--     타임존으로 변환된다. DB 컨테이너에도 TZ 설정이 없어 역시 UTC 다.
-- 즉 저장된 값은 UTC 벽시계이므로 UTC 로 읽어야 원래 순간이 보존된다.
--
-- identity_verify_cache 는 자바가 시각 타입으로 읽지 않는다(expires_at > now() 비교와
-- now() + INTERVAL 삽입 모두 SQL 안에서 끝난다). 그래서 이 테이블은 엔티티 변경이 없고,
-- 오히려 timestamptz 가 되면서 now() 와의 비교가 세션 타임존을 타지 않게 된다.
--
-- 나머지 두 테이블은 엔티티의 LocalDateTime 을 OffsetDateTime 으로 함께 바꾼다.
-- Postgres 드라이버는 TIMESTAMPTZ 를 LocalDateTime 으로 읽기를 거부한다
-- ("Cannot convert the column of type TIMESTAMPTZ to requested type java.time.LocalDateTime").
-- =============================================================================

ALTER TABLE loan_document_submission
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC';

ALTER TABLE loan_forgery_signal
    ALTER COLUMN detected_at TYPE TIMESTAMPTZ USING detected_at AT TIME ZONE 'UTC';

ALTER TABLE identity_verify_cache
    ALTER COLUMN verified_at TYPE TIMESTAMPTZ USING verified_at AT TIME ZONE 'UTC',
    ALTER COLUMN expires_at  TYPE TIMESTAMPTZ USING expires_at  AT TIME ZONE 'UTC';

-- DEFAULT 도 다시 못박는다. now() 는 timestamptz 를 돌려주므로 타입 변경 후에도 유효하지만,
-- 명시해 두어야 다음에 읽는 사람이 기본값의 타입을 의심하지 않는다.
ALTER TABLE loan_document_submission
    ALTER COLUMN created_at SET DEFAULT now(),
    ALTER COLUMN updated_at SET DEFAULT now();

ALTER TABLE loan_forgery_signal
    ALTER COLUMN detected_at SET DEFAULT now();

ALTER TABLE identity_verify_cache
    ALTER COLUMN verified_at SET DEFAULT now();
