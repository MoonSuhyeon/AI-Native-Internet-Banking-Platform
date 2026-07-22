-- 상담사(직원) 테이블 및 시드 데이터
-- consultation-service의 _validate_staff()가 이 테이블을 조회합니다.

BEGIN;

CREATE TABLE IF NOT EXISTS employees (
    employee_id     BIGSERIAL PRIMARY KEY,
    login_id        VARCHAR(50)  NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    name            VARCHAR(100) NOT NULL,
    role            VARCHAR(20)  NOT NULL DEFAULT 'AGENT',  -- AGENT, SUPERVISOR
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, INACTIVE
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 시드 데이터
-- 비밀번호: Agent1234!
INSERT INTO employees (employee_id, login_id, password_hash, name, role, status)
VALUES
    (1, 'agent01', '$2b$12$vW.ceUB2JPpvkSyYZf4VFeCXflhB0Y0dH0kSH78g6q7T/Djcvpme6', '김상담', 'AGENT',      'ACTIVE'),
    (2, 'agent02', '$2b$12$vW.ceUB2JPpvkSyYZf4VFeCXflhB0Y0dH0kSH78g6q7T/Djcvpme6', '이상담', 'AGENT',      'ACTIVE'),
    (3, 'super01', '$2b$12$vW.ceUB2JPpvkSyYZf4VFeCXflhB0Y0dH0kSH78g6q7T/Djcvpme6', '박팀장', 'SUPERVISOR', 'ACTIVE')
ON CONFLICT (login_id) DO NOTHING;

COMMIT;
