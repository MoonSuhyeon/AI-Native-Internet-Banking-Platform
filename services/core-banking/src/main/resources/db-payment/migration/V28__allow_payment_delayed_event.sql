-- outbox_message.event_type 에 PAYMENT_DELAYED 를 허용한다.
--
-- 이상거래 점검이 지연시킨 이체를 고객에게 알리는 이벤트다. 결제계는 발송하지 않고
-- 이 이벤트만 내며, 고객계가 소비해 알림을 만든다 — 결제 트랜잭션이 외부 발송 성공에
-- 묶이면 알림 장애가 곧 이체 장애가 되기 때문이다.
--
-- 값을 코드에 추가하면서 이 CHECK 를 넓히지 않아, 지연 판정이 나온 이체가 제약 위반으로
-- 실패했다. 이번 기능에서 같은 실수를 세 번 했다 — payment_instruction.trigger_source,
-- status_history.triggered_by, 그리고 여기다.
--
-- 세 곳 다 "코드에 새 값을 넣으면 DB 가 받아 주는지 확인" 이 빠져서 생겼다.
-- 단위 테스트는 매퍼를 목으로 대체하므로 제약까지 가지 않고, 실제로 그 경로를
-- 태워 봐야 드러난다. TriggerSourceConstraintTest 와 같은 방식의 검사가
-- 이 컬럼에도 있어야 한다.

ALTER TABLE payment.outbox_message
    DROP CONSTRAINT IF EXISTS chk_outbox_message_event_type;

ALTER TABLE payment.outbox_message
    ADD CONSTRAINT chk_outbox_message_event_type CHECK (
        event_type IN (
            'PAYMENT_REQUESTED',
            'PAYMENT_SCHEDULED',
            'PAYMENT_SCHEDULE_CANCELED',
            'KFTC_REQUEST_SENT',
            'KFTC_REJECTED',
            'KFTC_SETTLED',
            'BOK_REQUEST_SENT',
            'BOK_REJECTED',
            'BOK_CONFIRMED',
            'PAYMENT_REVERSED',
            'PAYMENT_COMPLETED',
            'PAYMENT_FAILED',
            'PAYMENT_CANCELED',
            'INBOUND_RECEIVED',
            'KFTC_ACK_SENT',
            'BOK_ACK_SENT',
            'KFTC_SETTLEMENT_SENT',
            'BOK_CONFIRM_SENT',
            'KFTC_REJECT_SENT',
            'BOK_REJECT_SENT',
            -- 이상거래 점검이 지연시킨 이체 (고객계가 소비해 알림 생성)
            'PAYMENT_DELAYED'
        )
    );
