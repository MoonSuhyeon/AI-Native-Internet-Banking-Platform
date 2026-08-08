-- 승인 토큰을 담을 수 있게 auth_token_id 를 넓힌다.
--
-- 배경: 이 컬럼은 브라우저가 만들던 옛 값('T' + 타임스탬프 + 난수, 20자 이내)에
-- 맞춰져 있었다. 그 값은 아무도 검증하지 않는, 이름만 인증 토큰이었다.
--
-- 자금이동 step-up 인증(docs/plan/transfer-step-up-auth.md)이 들어오면서
-- 인증보안계가 발급한 진짜 승인 토큰을 여기에 저장하게 됐는데, 그 토큰은
-- 43자다. 컬럼을 넓히는 마이그레이션이 함께 오지 않아, 토큰을 제시한 이체는
-- "value too long for type character varying(20)" 으로 500 이 났다.
--
-- 토큰 없이 보내는 소액 이체는 멀쩡히 동작해서 한동안 드러나지 않았다.
--
-- 64로 잡는다. 현재 토큰은 43자이고, 인코딩이 바뀌어도 여유가 있다.

ALTER TABLE payment.payment_instruction
    ALTER COLUMN auth_token_id TYPE VARCHAR(64);
