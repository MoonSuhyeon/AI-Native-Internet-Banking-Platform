-- trigger_source 에 FDS_DELAY 를 허용한다.
--
-- 이상거래 점검이 지연을 지시하면 즉시이체를 예약이체로 전환하는데, 그 예약 건에
-- "FDS 가 지연시킨 것" 이라는 표시를 남긴다. 표시가 없으면 나중에 취소를 세도
-- 그게 지연 때문인지 고객이 원래 예약해 둔 거래를 무른 것인지 구별할 수 없고,
-- 지연 장치가 사고를 막고 있는지 알 수 없다.
--
-- 값을 코드에 추가하면서 이 CHECK 를 함께 넓히지 않아, 지연 판정이 나온 이체가
-- 제약 위반으로 500 이 났다. 단위 테스트는 매퍼를 목으로 대체해 DB 제약까지
-- 가지 않으므로 잡히지 않았다 — 실제로 지연을 발동시켜 봐야 드러난다.

ALTER TABLE payment.payment_instruction
    DROP CONSTRAINT IF EXISTS chk_payment_instruction_trigger_source;

ALTER TABLE payment.payment_instruction
    ADD CONSTRAINT chk_payment_instruction_trigger_source CHECK (
        trigger_source IN (
            'USER',
            'AUTO_TRANSFER',
            'SCHEDULER',
            'OPERATOR',
            'COUNTERPARTY_BANK',
            -- 이상거래 점검이 지연시켜 예약으로 접수한 건
            'FDS_DELAY'
        )
    );
