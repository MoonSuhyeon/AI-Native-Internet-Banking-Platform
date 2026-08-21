-- BaseEntity 감사 컬럼을 새 표 셋에 채운다.
--
-- **무엇을 놓쳤나.** `FavoriteTransfer` 가 `BaseEntity` 를 상속하는데 V36 이 그
-- 컬럼들(created_by·updated_by·deleted_at·deleted_by·version)을 만들지 않았다.
-- 목록 조회가 곧바로 500 이 났다 — `column ft1_0.created_by does not exist`.
--
-- **왜 테스트가 못 잡았나.** 두 겹으로 새어 나갔다.
--
--   1. 단위 테스트는 Flyway 를 끄고 H2 가 **엔티티에서** 스키마를 만든다.
--      엔티티가 곧 스키마라 언제나 일치한다.
--   2. `EntitySchemaConsistencyTest` 는 Testcontainers 로 실제 마이그레이션을
--      올리지만 **테이블이 있는지만** 봤다. 컬럼은 보지 않았다.
--
-- 그래서 검사 쪽도 함께 고쳤다(컬럼까지 대조). 이 마이그레이션만 넣으면 다음에
-- 같은 실수를 또 한다.
--
-- deposit_alert_subscription·branch 계열은 BaseEntity 를 상속하지 않아 대상이
-- 아니다. 상속 여부를 확인하고 필요한 표에만 넣는다.

ALTER TABLE favorite_transfer
    ADD COLUMN IF NOT EXISTS created_by BIGINT,
    ADD COLUMN IF NOT EXISTS updated_by BIGINT,
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS deleted_by BIGINT,
    ADD COLUMN IF NOT EXISTS version    INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN favorite_transfer.version IS
    '낙관적 잠금. BaseEntity 가 요구한다.';

-- BaseEntity 는 created_at·updated_at 을 NOT NULL 로 본다. V36 이 DEFAULT now() 로
-- 만들었으므로 값은 채워져 있다.
ALTER TABLE favorite_transfer ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE favorite_transfer ALTER COLUMN updated_at SET NOT NULL;
