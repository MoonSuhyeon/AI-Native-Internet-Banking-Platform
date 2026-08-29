-- =============================================================
-- V38__employee_operation_log.sql
-- 직원 운영 작업 감사
--
-- 근거: docs/decisions/transaction-initiator-auth-model.md §7
--
--   최종 보안 모델은 모든 Core Banking internal API 에 인증을 요구한다. 단 호출
--   주체에 따라 서비스 신원과 직원 신원을 구분한다. 서비스 간 호출은 credential
--   기반 service principal + operation permission 으로, 직원 운영 작업은 직원
--   identity + RBAC 로 인가한다.
--
-- 왜 기존 표를 쓰지 않는가.
--
--   service_authorization_log  서비스 신원이 주어다. 직원 작업을 여기 넣으면
--                              service_id 가 늘 비고 행위자가 어디에도 안 남는다.
--   deposit_access_log (A2)    고객 데이터 열람 감사다. target_customer_id ·
--                              target_account_id 가 중심인데, 대사 실행에는
--                              대상 고객이 없다.
--
--   둘 다 모양이 맞지 않는다. 억지로 끼워 넣으면 두 표 모두 읽기 어려워진다.
--
-- 무엇을 남기는가.
--
--   허용도 거절도 남긴다. 그리고 무엇을 실행했는지(parameters)를 함께 남긴다 —
--   "누가 대사를 돌렸다" 만으로는 어느 영업일을 다시 돌렸는지 알 수 없고, 재실행이
--   흔한 작업이라 그 구분이 곧 조사의 출발점이다.
--
--   employee_id 는 NULL 을 허용한다. 신원 없이 들어온 시도도 남겨야 하기 때문이다.
--   그 경우가 오히려 더 봐야 할 기록이다.
-- =============================================================

CREATE TABLE employee_operation_log (

    log_id                  BIGSERIAL,

    -- ── 행위자 ─────────────────────────────────────────────────────────────
    employee_id             VARCHAR(30)     NULL,
    -- 게이트웨이가 실어 보낸 역할 목록(쉼표 결합). 판정 근거를 그대로 박제한다 —
    -- 나중에 역할 구성이 바뀌어도 "그때 무엇을 갖고 있었는가" 가 사실이다.
    actor_roles             VARCHAR(200)    NULL,

    -- ── 작업 ───────────────────────────────────────────────────────────────
    operation               VARCHAR(40)     NOT NULL,
    parameters              VARCHAR(300)    NULL,

    -- ── 판정 ───────────────────────────────────────────────────────────────
    decision                VARCHAR(20)     NOT NULL,
    deny_reason             VARCHAR(40)     NULL,

    trace_id                VARCHAR(64)     NULL,
    request_path            VARCHAR(200)    NULL,
    occurred_at             TIMESTAMPTZ     NOT NULL    DEFAULT now(),

    CONSTRAINT pk_employee_operation_log
        PRIMARY KEY (log_id),
    CONSTRAINT chk_employee_operation_log_decision
        CHECK (decision IN ('ALLOW', 'DENY')),
    -- 사유 없는 거절은 감사에서 쓸모가 없다.
    CONSTRAINT chk_employee_operation_log_deny_reason
        CHECK (decision <> 'DENY' OR deny_reason IS NOT NULL)
);

CREATE INDEX idx_employee_operation_log_actor_time
    ON employee_operation_log (employee_id, occurred_at DESC);
CREATE INDEX idx_employee_operation_log_denied
    ON employee_operation_log (occurred_at DESC)
    WHERE decision = 'DENY';

COMMENT ON TABLE  employee_operation_log            IS '직원 운영 작업 감사';
COMMENT ON COLUMN employee_operation_log.parameters IS '무엇을 실행했는가 (영업일·망 등)';


-- ── 감사 잠금 (WORM) ─────────────────────────────────────────────────────────
--
-- service_authorization_log(V31) · deposit_access_log(V28) · customer_access_log(V41)
-- 와 같은 계열이다. 한계도 같다 — 트리거는 애플리케이션 경로를 막을 뿐 DB 관리자를
-- 막지 못한다. 실제 통제는 계정 분리와 보존 스토리지가 함께 있어야 성립한다.

CREATE TRIGGER trg_employee_operation_log_no_update
    BEFORE UPDATE ON employee_operation_log
    FOR EACH ROW EXECUTE FUNCTION fn_service_audit_block_mutate();

CREATE TRIGGER trg_employee_operation_log_no_delete
    BEFORE DELETE ON employee_operation_log
    FOR EACH ROW EXECUTE FUNCTION fn_service_audit_block_mutate();
