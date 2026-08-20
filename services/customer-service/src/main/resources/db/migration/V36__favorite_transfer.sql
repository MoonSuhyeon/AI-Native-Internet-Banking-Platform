-- 자주쓰는계좌 · 단축이체 — 이체 화면의 기본 편의 기능.
--
-- 이체 화면에 "자주쓰는계좌 등록/삭제"·"단축이체 등록/삭제" 버튼이 있었지만 부를 곳이
-- 없었다. 레포 전체에서 favorite·즐겨찾기 관련 코드가 0건이었다.
--
-- **왜 한 테이블인가.** 둘 다 "고객이 미리 등록해 둔 이체 대상" 이고 저장하는 항목이
-- 같다. 다른 것은 쓰임 하나뿐이다.
--
--   자주쓰는계좌  이체 화면에서 고를 수 있는 목록
--   단축이체      금액까지 미리 정해 두고 한 번에 보내는 것
--
-- 그래서 종류를 컬럼으로 두고, 단축이체일 때만 금액을 요구한다. 테이블을 둘로 나누면
-- 같은 CRUD 를 두 벌 쓰게 되고, 한쪽만 고치는 일이 생긴다.

CREATE TABLE IF NOT EXISTS favorite_transfer (
    favorite_id        BIGSERIAL PRIMARY KEY,
    customer_id        BIGINT       NOT NULL,

    -- FAVORITE_ACCOUNT(자주쓰는계좌) · QUICK_TRANSFER(단축이체)
    favorite_type      VARCHAR(30)  NOT NULL,

    -- 고객이 알아보는 이름. "엄마", "월세" 처럼 본인만 아는 말이 들어간다.
    alias              VARCHAR(50)  NOT NULL,

    bank_code          VARCHAR(10)  NOT NULL,
    bank_name          VARCHAR(50)  NOT NULL,
    account_number     VARCHAR(50)  NOT NULL,
    account_holder_name VARCHAR(100),

    -- 단축이체에만 있다. 자주쓰는계좌는 금액을 그때 정한다.
    amount             BIGINT,

    -- 목록에서 보이는 순서. 자주 쓰는 것을 위로 올릴 수 있어야 편의 기능이 된다.
    display_order      INT          NOT NULL DEFAULT 0,

    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  favorite_transfer IS '이체 즐겨찾기. 자주쓰는계좌와 단축이체를 종류로 구분한다.';
COMMENT ON COLUMN favorite_transfer.amount IS
    '단축이체 금액. 자주쓰는계좌는 NULL 이며, 단축이체는 반드시 있어야 한다.';

ALTER TABLE favorite_transfer
    DROP CONSTRAINT IF EXISTS ck_favorite_type;
ALTER TABLE favorite_transfer
    ADD CONSTRAINT ck_favorite_type
    CHECK (favorite_type IN ('FAVORITE_ACCOUNT', 'QUICK_TRANSFER'));

-- 단축이체는 금액이 있어야 하고, 자주쓰는계좌는 없어야 한다.
-- 애플리케이션만 막으면 다른 경로(배치·수동 SQL)로 들어온 값이 통과한다.
ALTER TABLE favorite_transfer
    DROP CONSTRAINT IF EXISTS ck_favorite_amount;
ALTER TABLE favorite_transfer
    ADD CONSTRAINT ck_favorite_amount
    CHECK ((favorite_type = 'QUICK_TRANSFER' AND amount IS NOT NULL AND amount > 0)
        OR (favorite_type = 'FAVORITE_ACCOUNT' AND amount IS NULL));

-- 같은 계좌를 같은 종류로 두 번 등록하면 목록에 같은 줄이 두 개 보인다.
CREATE UNIQUE INDEX IF NOT EXISTS ux_favorite_transfer_target
    ON favorite_transfer (customer_id, favorite_type, bank_code, account_number);

CREATE INDEX IF NOT EXISTS ix_favorite_transfer_list
    ON favorite_transfer (customer_id, favorite_type, display_order);
