-- V27: 감사에 "무엇을 요청했고, 누가 무엇이라 판단했는가" 를 남긴다.
--
-- V26 은 결과(ALLOWED/DENIED)만 남겼다. 그런데 이 경로에는 판단이 둘 있다.
--   ① customer-service 의 인가 판단 — 직원 신원·역할 기준
--   ② core-banking 의 자원 접근 판단 — 자기 자원 기준 (최종 경계)
--
-- 하나로 뭉치면 "상류는 허용했는데 자원 쪽에서 막혔다" 를 구별할 수 없다.
-- 그 구별이 사라지면 중앙 인가가 잘못 열렸을 때 그 사실이 로그에서 안 보인다.
--
-- resource/action 도 나눈다. access_action_code 하나에 'ACCOUNT_LIST' 처럼 뭉쳐 두면
-- "계좌 자원에 대한 읽기" 를 자원 축으로 집계할 수 없다.

ALTER TABLE deposit_access_log
    ADD COLUMN resource_code    VARCHAR(40),
    ADD COLUMN action_code      VARCHAR(20),
    ADD COLUMN authz_decision   VARCHAR(20),
    ADD COLUMN authz_deny_code  VARCHAR(40);

COMMENT ON COLUMN deposit_access_log.authz_decision IS
    'customer-service 인가 판단 결과. core-banking 은 이것만 믿고 자원을 내주지 않는다';
COMMENT ON COLUMN deposit_access_log.result_code IS
    'core-banking 의 최종 판단. 자원 접근의 마지막 경계는 자원을 가진 쪽에 있다';

-- 상류는 허용했는데 자원 쪽에서 막힌 건 — 중앙 인가가 잘못 열렸는지 보는 자리
CREATE INDEX idx_deposit_access_log_authz_gap
    ON deposit_access_log (accessed_at DESC)
    WHERE authz_decision = 'ALLOW' AND result_code = 'DENIED';
