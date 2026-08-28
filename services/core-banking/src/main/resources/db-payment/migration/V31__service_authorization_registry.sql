-- =============================================================
-- V31__service_authorization_registry.sql
-- 서비스 간 인가(service-to-service authorization) 레지스트리
--
-- 근거: docs/decisions/transaction-initiator-auth-model.md
--
-- 왜 필요한가.
--   지금 core-banking 은 SecurityConfig 가 anyRequest().permitAll() 이다.
--   "게이트웨이가 JWT 인증하니 백엔드는 통과" 라는 전제인데, 서비스 간 호출(동서)은
--   게이트웨이를 지나지 않는다. 즉 내부 호출에는 인증이 없다.
--
--   기존 X-Internal-Token 방식(loan·fds)도 부족하다. 토큰 하나를 모두가 공유하고
--   principal 이 'internal-service' 하나뿐이라, 그 토큰을 가진 무엇이든 모든 내부
--   작업을 할 수 있다. 서비스 계정은 사람 계정보다 오래 살고 회전이 적어, 권한이
--   넓으면 사고 시 영향 범위가 그만큼 넓다.
--
-- 두 가지를 여기서 세운다.
--   1) 신원 — 어떤 서비스가 불렀는가
--   2) 인가 — 그 서비스가 이 작업을 할 수 있는가 (+ 금액·계좌 정책)
--
-- 왜 payment 스키마인가.
--   인가 판정이 다른 데이터베이스를 타면 그 DB 가 결제 경로의 단일 장애점이 된다.
--   판정 코드는 재사용하되 데이터는 강제하는 서비스가 소유한다. deposit·customer 도
--   각자 자기 스키마에 같은 모양을 갖는다.
-- =============================================================


-- ── 1. 서비스 신원 ────────────────────────────────────────────────────────────
--
-- 신원은 주장이 아니라 자격증명에서 나온다. X-Service-Id 같은 헤더를 믿으면,
-- 토큰 하나를 가진 아무 서비스나 다른 서비스를 사칭할 수 있다. 그래서 토큰 해시가
-- 곧 신원이고(UNIQUE), 호출자는 자기가 누구인지 말할 필요가 없다.
--
-- 원문 토큰은 저장하지 않는다. 유출 시 DB 덤프만으로 호출할 수 있으면 안 된다.

CREATE TABLE service_principal (
    service_id              VARCHAR(30)     NOT NULL,
    display_name            VARCHAR(60)     NOT NULL,
    credential_hash         VARCHAR(64)     NOT NULL,
    status                  VARCHAR(20)     NOT NULL        DEFAULT 'ACTIVE',

    created_at              TIMESTAMPTZ     NOT NULL        DEFAULT now(),
    created_by              VARCHAR(30)     NOT NULL,
    updated_at              TIMESTAMPTZ     NOT NULL        DEFAULT now(),
    updated_by              VARCHAR(30)     NOT NULL,

    CONSTRAINT pk_service_principal
        PRIMARY KEY (service_id),
    CONSTRAINT uq_service_principal_credential
        UNIQUE (credential_hash),
    CONSTRAINT chk_service_principal_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

COMMENT ON TABLE  service_principal                  IS '서비스 신원';
COMMENT ON COLUMN service_principal.service_id       IS '서비스 식별자';
COMMENT ON COLUMN service_principal.credential_hash  IS '자격증명 SHA-256 (원문 미저장)';
COMMENT ON COLUMN service_principal.status           IS '상태 (ACTIVE/SUSPENDED)';


-- ── 2. 작업 권한 — 정본 ──────────────────────────────────────────────────────
--
-- 코드 상수나 설정 파일이 아니라 DB 가 정본이다. 권한을 바꾸려고 배포해야 한다면
-- "누가 언제 이 권한을 줬는가" 가 커밋 로그에만 남는데, 감독 검사가 요구하는 것은
-- 커밋 로그가 아니라 권한 부여 이력이다.
--
-- max_amount 를 NULL 로 두면 무제한이다. 권장하지 않으므로 열 주석에 남긴다 —
-- 무제한 권한이 필요하면 그것이 의도임을 사람이 확인하고 넣어야 한다.

CREATE TABLE service_permission (
    permission_id           BIGSERIAL,
    service_id              VARCHAR(30)     NOT NULL,
    operation               VARCHAR(40)     NOT NULL,
    max_amount              BIGINT          NULL,
    status                  VARCHAR(20)     NOT NULL        DEFAULT 'ACTIVE',

    granted_at              TIMESTAMPTZ     NOT NULL        DEFAULT now(),
    granted_by              VARCHAR(30)     NOT NULL,
    revoked_at              TIMESTAMPTZ     NULL,
    revoked_by              VARCHAR(30)     NULL,

    CONSTRAINT pk_service_permission
        PRIMARY KEY (permission_id),
    CONSTRAINT fk_service_permission_principal
        FOREIGN KEY (service_id) REFERENCES service_principal (service_id),
    CONSTRAINT uq_service_permission
        UNIQUE (service_id, operation),
    CONSTRAINT chk_service_permission_status
        CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT chk_service_permission_max_amount
        CHECK (max_amount IS NULL OR max_amount > 0),
    CONSTRAINT chk_service_permission_revoked_consistency
        CHECK (status <> 'REVOKED' OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL))
);

COMMENT ON TABLE  service_permission             IS '서비스 작업 권한 (정본)';
COMMENT ON COLUMN service_permission.operation   IS '작업 (LOAN_DISBURSE 등)';
COMMENT ON COLUMN service_permission.max_amount  IS '건당 한도. NULL 은 무제한 — 권장하지 않는다';


-- ── 3. 계좌 정책 ─────────────────────────────────────────────────────────────
--
-- 여신 집행은 집행계좌에서만 나가야 한다. 작업 권한만 있고 계좌 제한이 없으면,
-- 권한을 가진 서비스가 아무 고객 계좌에서나 자금을 뺄 수 있다.
--
-- 행이 하나도 없으면 계좌를 제한하지 않는다는 뜻이다. 이 역시 의도적으로만
-- 비워 두어야 한다.

CREATE TABLE service_permission_account (
    permission_id           BIGINT          NOT NULL,
    account_no              VARCHAR(30)     NOT NULL,

    created_at              TIMESTAMPTZ     NOT NULL        DEFAULT now(),
    created_by              VARCHAR(30)     NOT NULL,

    CONSTRAINT pk_service_permission_account
        PRIMARY KEY (permission_id, account_no),
    CONSTRAINT fk_service_permission_account_permission
        FOREIGN KEY (permission_id) REFERENCES service_permission (permission_id)
);

COMMENT ON TABLE service_permission_account IS '작업별 허용 송신계좌. 행이 없으면 제한 없음';


-- ── 4. 권한 부여·변경 감사 ───────────────────────────────────────────────────
--
-- 감사는 두 겹이다. 이것은 [1] 변경 이력 — 드물게 일어나고 오래 보존한다.
-- [2] 판정 감사는 아래 service_authorization_log 다. 둘은 성격이 달라 따로 남긴다.

CREATE TABLE service_permission_history (
    history_id              BIGSERIAL,
    service_id              VARCHAR(30)     NOT NULL,
    operation               VARCHAR(40)     NOT NULL,
    action                  VARCHAR(20)     NOT NULL,
    before_state            TEXT            NULL,
    after_state             TEXT            NULL,
    reason                  VARCHAR(200)    NOT NULL,
    actor                   VARCHAR(30)     NOT NULL,
    occurred_at             TIMESTAMPTZ     NOT NULL        DEFAULT now(),

    CONSTRAINT pk_service_permission_history
        PRIMARY KEY (history_id),
    CONSTRAINT chk_service_permission_history_action
        CHECK (action IN ('GRANT', 'REVOKE', 'MODIFY'))
);

COMMENT ON TABLE service_permission_history IS '권한 부여·변경 감사 [1]';


-- ── 5. 인가 판정 감사 ────────────────────────────────────────────────────────
--
-- 거래마다 남는다. [2]
--
-- 거절을 빼면 안 된다. 권한 없는 서비스가 반복 호출한 흔적이 사라지면, 그것이
-- 설정 실수인지 침해 시도인지 나중에 구분할 수 없다.
--
-- service_id 는 NULL 을 허용한다 — 자격증명 자체가 틀려 신원을 세우지 못한 호출도
-- 남겨야 하기 때문이다. 그 경우가 오히려 더 봐야 할 기록이다.

CREATE TABLE service_authorization_log (
    log_id                  BIGSERIAL,
    service_id              VARCHAR(30)     NULL,
    operation               VARCHAR(40)     NULL,
    decision                VARCHAR(20)     NOT NULL,
    deny_reason             VARCHAR(40)     NULL,

    idempotency_key         VARCHAR(50)     NULL,
    amount                  BIGINT          NULL,
    sender_account_no       VARCHAR(30)     NULL,
    trace_id                VARCHAR(64)     NULL,
    request_path            VARCHAR(200)    NULL,

    occurred_at             TIMESTAMPTZ     NOT NULL        DEFAULT now(),

    CONSTRAINT pk_service_authorization_log
        PRIMARY KEY (log_id),
    CONSTRAINT chk_service_authorization_log_decision
        CHECK (decision IN ('ALLOW', 'DENY')),
    CONSTRAINT chk_service_authorization_log_deny_reason
        CHECK (decision <> 'DENY' OR deny_reason IS NOT NULL)
);

CREATE INDEX idx_service_authorization_log_service_time
    ON service_authorization_log (service_id, occurred_at DESC);
CREATE INDEX idx_service_authorization_log_denied
    ON service_authorization_log (occurred_at DESC)
    WHERE decision = 'DENY';

COMMENT ON TABLE service_authorization_log IS '인가 판정 감사 [2]. 거절도 남긴다';


-- ── 6. 감사 테이블 잠금 (WORM) ───────────────────────────────────────────────
--
-- deposit_access_log(V28) · customer_access_log(V41) 와 같은 계열이다.
-- 한계도 같다 — 트리거는 애플리케이션 경로를 막을 뿐 DB 관리자를 막지 못한다.
-- 실제 통제는 계정 분리와 보존 스토리지가 함께 있어야 성립한다.
--
-- 권한 레지스트리(service_permission)는 잠그지 않는다. 권한은 회수돼야 하기
-- 때문이다. 대신 변경이 history 에 남는 것을 애플리케이션이 보장한다.

CREATE OR REPLACE FUNCTION fn_service_audit_block_mutate()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION '% is INSERT-ONLY. UPDATE/DELETE is forbidden.', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_service_permission_history_no_update
    BEFORE UPDATE ON service_permission_history
    FOR EACH ROW EXECUTE FUNCTION fn_service_audit_block_mutate();

CREATE TRIGGER trg_service_permission_history_no_delete
    BEFORE DELETE ON service_permission_history
    FOR EACH ROW EXECUTE FUNCTION fn_service_audit_block_mutate();

CREATE TRIGGER trg_service_authorization_log_no_update
    BEFORE UPDATE ON service_authorization_log
    FOR EACH ROW EXECUTE FUNCTION fn_service_audit_block_mutate();

CREATE TRIGGER trg_service_authorization_log_no_delete
    BEFORE DELETE ON service_authorization_log
    FOR EACH ROW EXECUTE FUNCTION fn_service_audit_block_mutate();
