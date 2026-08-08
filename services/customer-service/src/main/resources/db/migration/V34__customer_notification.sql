-- 고객 알림함.
--
-- 왜 필요한가: 이상거래 점검이 이체를 지연시키면 고객이 알아채고 취소할 수 있어야
-- 그 장치가 뜻을 갖는다. 지연시켜 놓고 아무도 모르면 시간만 지나고 그대로 나간다.
--
-- 왜 고객계인가: 연락처와 수신 설정(sms/email/postal)을 가진 곳이 여기다.
-- 결제계는 이벤트만 내고 발송 판단은 하지 않는다 — 결제 트랜잭션이 발송 성공에
-- 묶이면 알림 장애가 이체 장애가 된다.

CREATE TABLE IF NOT EXISTS customer_notification (
    notification_id   BIGSERIAL    PRIMARY KEY,
    customer_id       BIGINT       NOT NULL,

    -- 알림 종류. PAYMENT_DELAYED 등. 화면이 종류별로 다르게 그린다.
    notification_type VARCHAR(40)  NOT NULL,
    title             VARCHAR(200) NOT NULL,
    body              VARCHAR(1000) NOT NULL,

    -- 이 알림이 가리키는 대상(결제지시번호 등). 화면에서 상세로 이동하는 데 쓴다.
    reference_id      VARCHAR(64),

    -- 조치 기한. 지연 이체의 실행 예정 시각처럼 "언제까지 손쓸 수 있는가".
    -- 지나면 화면에서 취소 버튼을 감춰야 한다.
    action_due_at     TIMESTAMPTZ,

    is_read           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at           TIMESTAMPTZ
);

-- 목록 조회는 언제나 "내 알림을 최신순으로" 다.
CREATE INDEX IF NOT EXISTS ix_customer_notification_customer
    ON customer_notification (customer_id, created_at DESC);

-- 같은 이벤트가 두 번 와도 알림이 두 개 생기지 않게 한다.
-- Kafka 는 at-least-once 라 재처리가 정상 상황인데, 그대로 두면 고객에게
-- 같은 알림이 여러 번 뜬다. 예외도 안 나고 아무도 모른다.
CREATE UNIQUE INDEX IF NOT EXISTS ux_customer_notification_dedupe
    ON customer_notification (notification_type, reference_id)
    WHERE reference_id IS NOT NULL;
