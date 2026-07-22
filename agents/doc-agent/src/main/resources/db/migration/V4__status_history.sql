-- 공용 엔티티 com.bank.common.audit.StatusHistory 매핑 테이블.
-- 분산 보관 정책: 각 도메인 DB 가 자체 status_history 테이블을 보유한다.
-- (loan-service V1 의 status_history 와 동일 스키마)
CREATE TABLE status_history (
    sthist_id         BIGSERIAL    PRIMARY KEY,
    target_domain_cd  VARCHAR(30)  NOT NULL,
    target_table_cd   VARCHAR(50)  NOT NULL,
    target_id         BIGINT       NOT NULL,
    before_status_cd  VARCHAR(50),
    after_status_cd   VARCHAR(50)  NOT NULL,
    change_reason_cd  VARCHAR(50),
    change_remark     VARCHAR(500),
    changed_at        TIMESTAMPTZ  NOT NULL,
    changed_by        BIGINT       NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        BIGINT       NOT NULL
);

CREATE INDEX idx_status_history_target
    ON status_history (target_domain_cd, target_table_cd, target_id, changed_at);
