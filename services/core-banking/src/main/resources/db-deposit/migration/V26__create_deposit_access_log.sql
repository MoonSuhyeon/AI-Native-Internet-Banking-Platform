-- V26: 수신 데이터 열람 감사.
--
-- core-banking 에는 조회 감사가 없었다. 고객 정보 열람 이력은 customer-service 의
-- customer_access_log 에만 있고, 계좌·잔액·거래를 누가 봤는지는 어디에도 안 남았다.
--
-- 그래서 "AI 상담이 조회한 고객 금융정보 열람 이력을 제출하라" 에 답할 수 없었다.
-- consultation 이 DB 를 직접 읽던 것을 API 로 돌려도, 그 API 에 감사가 없으면
-- "DB 는 안 보지만 감사도 안 남는 API" 를 새로 만드는 것에 불과하다.
--
-- 컬럼 구성은 customer_access_log(V19) 와 맞췄다. 두 감사 테이블의 모양이 다르면
-- 감사 화면과 질의를 두 벌 만들어야 한다.

CREATE TABLE deposit_access_log (
    deposit_access_log_id   BIGINT         GENERATED ALWAYS AS IDENTITY,
    actor_type              VARCHAR(20)    NOT NULL,   -- EMPLOYEE / CUSTOMER / SERVICE
    actor_employee_id       BIGINT,                    -- 직원 대리 조회일 때 (X-Employee-Id)
    actor_customer_id       VARCHAR(30),               -- 본인 조회일 때 (X-Customer-Id)
    actor_service           VARCHAR(60),               -- 호출 서비스 이름 (consultation 등)
    target_customer_id      VARCHAR(30),               -- 조회 대상 고객
    target_account_id       BIGINT,                    -- 계좌 단위 조회일 때
    access_action_code      VARCHAR(40)    NOT NULL,   -- ACCOUNT_LIST / BALANCE_VIEW / TRANSACTION_LIST ...
    access_reason           VARCHAR(500),              -- 조회 사유 (X-Access-Reason)
    result_code             VARCHAR(20)    NOT NULL,   -- ALLOWED / DENIED
    denied_reason           VARCHAR(200),              -- 거절 사유. 거절도 남겨야 시도가 보인다
    trace_id                VARCHAR(64),               -- 요청 추적 상관키
    accessed_at             TIMESTAMPTZ(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_deposit_access_log PRIMARY KEY (deposit_access_log_id),
    CONSTRAINT chk_deposit_access_log_actor
        CHECK (actor_type IN ('EMPLOYEE', 'CUSTOMER', 'SERVICE')),
    CONSTRAINT chk_deposit_access_log_result
        CHECK (result_code IN ('ALLOWED', 'DENIED'))
);

-- 대상 고객별 열람 이력 (감사 1순위 질의)
CREATE INDEX idx_deposit_access_log_target   ON deposit_access_log (target_customer_id);
-- 행위자별 열람 이력 — "이 직원이 무엇을 봤는가"
CREATE INDEX idx_deposit_access_log_employee ON deposit_access_log (actor_employee_id);
-- 최근순 (감사 화면 기본 정렬)
CREATE INDEX idx_deposit_access_log_at       ON deposit_access_log (accessed_at DESC);
-- 거절만 추려 보기 — 권한 없는 시도가 몰리는지
CREATE INDEX idx_deposit_access_log_denied   ON deposit_access_log (result_code, accessed_at DESC)
    WHERE result_code = 'DENIED';
