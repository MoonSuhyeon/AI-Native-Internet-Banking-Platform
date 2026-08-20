-- 입금통보 신청 — 계좌에 돈이 들어오면 알려 달라는 고객의 등록.
--
-- 계좌 상세와 다온 계좌 화면에 "입금통보 신청" 버튼이 있었지만 핸들러가 없었다.
-- 알림함(customer_notification)은 이미 있으나 그건 *발송된 알림*을 담는 곳이고,
-- **누가 어느 계좌를 어디로 받겠다고 했는지**를 담는 곳이 없었다.
--
-- 이 표는 신청까지를 담당한다. 실제 발송은 코어뱅킹이 입금 이벤트를 내보내야
-- 시작되는데 지금 그 이벤트가 없다 — docs/OPEN_ITEMS.md 에 적어 둔다. 신청을
-- 먼저 만드는 이유는, 발송 경로가 생겼을 때 "누구에게" 를 물어볼 곳이 필요해서다.

CREATE TABLE IF NOT EXISTS deposit_alert_subscription (
    subscription_id  BIGSERIAL    PRIMARY KEY,
    customer_id      BIGINT       NOT NULL,
    account_number   VARCHAR(50)  NOT NULL,
    channel_cd       VARCHAR(20)  NOT NULL,
    contact          VARCHAR(255) NOT NULL,
    -- 이 금액 이상 입금만 알린다. 0 이면 전부.
    min_amount       BIGINT       NOT NULL DEFAULT 0,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE deposit_alert_subscription IS '입금통보 신청. 발송이 아니라 수신 의사를 담는다.';
COMMENT ON COLUMN deposit_alert_subscription.channel_cd IS 'SMS · PUSH · EMAIL. 앱 알림함(PUSH)만 지금 발송 경로가 있다.';
COMMENT ON COLUMN deposit_alert_subscription.contact IS '통보받을 곳. PUSH 는 고객번호로 충분하지만 SMS·EMAIL 은 값이 필요하다.';
COMMENT ON COLUMN deposit_alert_subscription.min_amount IS '이 금액 이상만 통보. 0 이면 전액. 소액 입금까지 다 오면 고객이 알림을 꺼 버린다.';

-- 채널을 오타로 넣으면 발송 시점에 조용히 아무 데도 안 간다. 그때는 원인을 찾기 어렵다.
ALTER TABLE deposit_alert_subscription
    DROP CONSTRAINT IF EXISTS ck_deposit_alert_channel;
ALTER TABLE deposit_alert_subscription
    ADD CONSTRAINT ck_deposit_alert_channel
    CHECK (channel_cd IN ('SMS', 'PUSH', 'EMAIL'));

-- 음수 기준액은 뜻이 없다. 전부 받겠다는 뜻은 0 이다.
ALTER TABLE deposit_alert_subscription
    DROP CONSTRAINT IF EXISTS ck_deposit_alert_min_amount;
ALTER TABLE deposit_alert_subscription
    ADD CONSTRAINT ck_deposit_alert_min_amount
    CHECK (min_amount >= 0);

-- 같은 계좌를 같은 채널로 두 번 신청하면 알림이 두 번 간다.
CREATE UNIQUE INDEX IF NOT EXISTS ux_deposit_alert_account_channel
    ON deposit_alert_subscription (customer_id, account_number, channel_cd);

-- 발송 시점 조회: 계좌번호로 살아 있는 신청을 찾는다.
CREATE INDEX IF NOT EXISTS ix_deposit_alert_account_active
    ON deposit_alert_subscription (account_number)
    WHERE active;
