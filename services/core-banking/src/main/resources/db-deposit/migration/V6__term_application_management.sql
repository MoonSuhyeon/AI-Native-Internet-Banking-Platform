-- ERD 기준 누락 테이블 추가: deposit_term_application_management (약관 적용 관리)
-- common_term_id : 공통 서비스 약관 ID (MVP: 외부 서비스 미구현으로 FK 미적용)
-- term_target_id : 약관 적용 대상 ID   (MVP: 대상 테이블 별도 관리로 FK 미적용)

BEGIN;

CREATE TABLE IF NOT EXISTS deposit_term_application_management (
    term_application_id  BIGSERIAL    PRIMARY KEY,
    common_term_id       BIGINT,
    term_target_id       BIGINT,
    business_type_code   VARCHAR(10),
    is_required          CHAR(1)      NOT NULL DEFAULT 'N',
    registered_at        CHAR(8),
    modified_at          CHAR(8),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by           VARCHAR(100),
    updated_at           TIMESTAMPTZ,
    updated_by           VARCHAR(100)
);

COMMENT ON TABLE  deposit_term_application_management                    IS '약관 적용 관리';
COMMENT ON COLUMN deposit_term_application_management.common_term_id    IS '공통 약관 ID (외부 서비스 참조)';
COMMENT ON COLUMN deposit_term_application_management.term_target_id    IS '약관 적용 대상 ID';
COMMENT ON COLUMN deposit_term_application_management.business_type_code IS '업무 구분 코드 (DEPOSIT/SAVINGS/SUBSCRIPTION)';
COMMENT ON COLUMN deposit_term_application_management.is_required        IS '필수 여부 Y/N';
COMMENT ON COLUMN deposit_term_application_management.registered_at      IS '등록일 YYYYMMDD';
COMMENT ON COLUMN deposit_term_application_management.modified_at        IS '수정일 YYYYMMDD';

COMMIT;
