-- ERD 기준: teamproject.drawio
-- deposit-service 전체 스키마 (chatbot_node_flow 제외)

BEGIN;

-- =============================================
-- 부서
-- =============================================
CREATE TABLE deposit_departments (
    department_id       BIGSERIAL    PRIMARY KEY,
    department_code     VARCHAR(50)  NOT NULL UNIQUE,
    department_name     VARCHAR(100) NOT NULL,
    parent_department_id BIGINT,
    department_type     VARCHAR(30),
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by          VARCHAR(100),
    updated_at          TIMESTAMPTZ,
    updated_by          VARCHAR(100),
    CONSTRAINT fk_deposit_departments_parent
        FOREIGN KEY (parent_department_id)
        REFERENCES deposit_departments (department_id)
        ON DELETE SET NULL ON UPDATE CASCADE
);

-- =============================================
-- 수신 상품 (메인)
-- =============================================
CREATE TABLE deposit_banking_products (
    banking_product_id          BIGSERIAL    PRIMARY KEY,
    deposit_product_type        VARCHAR(30)  NOT NULL,
    deposit_product_name        VARCHAR(200) NOT NULL,
    description                 TEXT,
    department_id               BIGINT,
    base_interest_rate          NUMERIC(5,2) NOT NULL DEFAULT 0,
    preferential_rate_condition TEXT,
    min_join_amount             NUMERIC(18,2),
    max_join_amount             NUMERIC(18,2),
    min_period_month            INT,
    max_period_month            INT,
    is_early_termination_allowed BOOLEAN     NOT NULL DEFAULT FALSE,
    is_tax_benefit_available    BOOLEAN      NOT NULL DEFAULT FALSE,
    is_auto_renewal_available   BOOLEAN      NOT NULL DEFAULT FALSE,
    is_passbook_issued          BOOLEAN      NOT NULL DEFAULT FALSE,
    released_at                 CHAR(8),
    ended_at                    CHAR(8),
    deposit_product_status      VARCHAR(20)  NOT NULL DEFAULT 'SELLING',
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by                  VARCHAR(100),
    updated_at                  TIMESTAMPTZ,
    updated_by                  VARCHAR(100),
    CONSTRAINT fk_deposit_banking_products_dept
        FOREIGN KEY (department_id)
        REFERENCES deposit_departments (department_id)
        ON DELETE SET NULL ON UPDATE CASCADE
);

-- =============================================
-- 예금 상품
-- =============================================
CREATE TABLE banking_deposit_products (
    deposit_product_id   BIGSERIAL   PRIMARY KEY,
    banking_product_id   BIGINT      NOT NULL,
    deposit_type         VARCHAR(20) NOT NULL,
    is_compound_interest BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by           VARCHAR(100),
    updated_at           TIMESTAMPTZ,
    updated_by           VARCHAR(100),
    CONSTRAINT fk_banking_deposit_products_product
        FOREIGN KEY (banking_product_id)
        REFERENCES deposit_banking_products (banking_product_id)
        ON DELETE CASCADE ON UPDATE CASCADE
);

-- =============================================
-- 적금 상품
-- =============================================
CREATE TABLE deposit_savings_products (
    savings_product_id         BIGSERIAL    PRIMARY KEY,
    banking_product_id         BIGINT       NOT NULL,
    saving_type                VARCHAR(20)  NOT NULL,
    monthly_payment_min_amount NUMERIC(18,2),
    monthly_payment_max_amount NUMERIC(18,2),
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by                 VARCHAR(100),
    updated_at                 TIMESTAMPTZ,
    updated_by                 VARCHAR(100),
    CONSTRAINT fk_deposit_savings_products_product
        FOREIGN KEY (banking_product_id)
        REFERENCES deposit_banking_products (banking_product_id)
        ON DELETE CASCADE ON UPDATE CASCADE
);

-- =============================================
-- 청약 상품
-- =============================================
CREATE TABLE deposit_subscription_products (
    banking_product_id             BIGINT       PRIMARY KEY,
    monthly_payment_amount         NUMERIC(18,2) NOT NULL,
    min_monthly_payment            NUMERIC(18,2),
    max_monthly_payment            NUMERIC(18,2),
    max_recognized_payment_amount  NUMERIC(18,2),
    created_at                     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by                     VARCHAR(100),
    updated_at                     TIMESTAMPTZ,
    updated_by                     VARCHAR(100),
    CONSTRAINT fk_deposit_subscription_products_product
        FOREIGN KEY (banking_product_id)
        REFERENCES deposit_banking_products (banking_product_id)
        ON DELETE CASCADE ON UPDATE CASCADE
);

-- =============================================
-- 상품 가입 방식
-- =============================================
CREATE TABLE banking_deposit_product_join_channels (
    channel_id         BIGSERIAL   PRIMARY KEY,
    banking_product_id BIGINT      NOT NULL,
    join_channel_code  VARCHAR(20) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(100),
    updated_at         TIMESTAMPTZ,
    updated_by         VARCHAR(100),
    CONSTRAINT fk_bdp_join_channels_product
        FOREIGN KEY (banking_product_id)
        REFERENCES deposit_banking_products (banking_product_id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT uq_bdp_join_channels_product_channel UNIQUE (banking_product_id, join_channel_code)
);

-- =============================================
-- 상품 금리
-- =============================================
CREATE TABLE banking_deposit_product_interest_rates (
    rate_id                  BIGSERIAL    PRIMARY KEY,
    banking_product_id       BIGINT       NOT NULL,
    rate_type                VARCHAR(30)  NOT NULL,
    minimum_contract_period  INT,
    maximum_contract_period  INT,
    minimum_join_amount      NUMERIC(18,2),
    maximum_join_amount      NUMERIC(18,2),
    rate                     NUMERIC(5,2) NOT NULL,
    condition_description    TEXT,
    effective_start_date     CHAR(8)      NOT NULL,
    effective_end_date       CHAR(8),
    is_active                BOOLEAN      NOT NULL DEFAULT TRUE,
    status                   VARCHAR(20),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by               VARCHAR(100),
    updated_at               TIMESTAMPTZ,
    updated_by               VARCHAR(100),
    CONSTRAINT fk_bdp_interest_rates_product
        FOREIGN KEY (banking_product_id)
        REFERENCES deposit_banking_products (banking_product_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
);

-- =============================================
-- 가입 대상 그룹
-- =============================================
CREATE TABLE deposit_target_groups (
    target_group_id   BIGSERIAL    PRIMARY KEY,
    target_group_name VARCHAR(100) NOT NULL UNIQUE,
    description       TEXT,
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by        VARCHAR(100),
    updated_at        TIMESTAMPTZ,
    updated_by        VARCHAR(100)
);

-- =============================================
-- 상품 가입 대상 (N:M)
-- =============================================
CREATE TABLE banking_deposit_product_target_groups (
    banking_product_id BIGINT      NOT NULL,
    target_group_id    BIGINT      NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(100),
    updated_at         TIMESTAMPTZ,
    updated_by         VARCHAR(100),
    PRIMARY KEY (banking_product_id, target_group_id),
    CONSTRAINT fk_bdp_target_groups_product
        FOREIGN KEY (banking_product_id)
        REFERENCES deposit_banking_products (banking_product_id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_bdp_target_groups_target
        FOREIGN KEY (target_group_id)
        REFERENCES deposit_target_groups (target_group_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
);

-- =============================================
-- 수신 특약
-- =============================================
CREATE TABLE deposit_special_terms (
    special_term_id                  BIGSERIAL    PRIMARY KEY,
    special_term_name                VARCHAR(200) NOT NULL,
    special_term_content             TEXT         NOT NULL,
    special_term_summary             TEXT,
    is_required                      BOOLEAN      NOT NULL DEFAULT FALSE,
    is_electronic_agreement_allowed  BOOLEAN      NOT NULL DEFAULT TRUE,
    special_term_version             VARCHAR(20)  NOT NULL,
    started_at                       CHAR(8),
    ended_at                         CHAR(8),
    status                           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    status_changed_at                CHAR(8),
    created_at                       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by                       VARCHAR(100),
    updated_at                       TIMESTAMPTZ,
    updated_by                       VARCHAR(100),
    CONSTRAINT uq_deposit_special_terms_name_ver UNIQUE (special_term_name, special_term_version)
);

-- =============================================
-- 수신 상품 특약 연결
-- =============================================
CREATE TABLE banking_deposit_product_special_terms (
    deposit_product_special_term_id BIGSERIAL   PRIMARY KEY,
    banking_product_id              BIGINT       NOT NULL,
    special_term_id                 BIGINT       NOT NULL,
    is_required                     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at                      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by                      VARCHAR(100),
    updated_at                      TIMESTAMPTZ,
    updated_by                      VARCHAR(100),
    CONSTRAINT fk_bdp_special_terms_product
        FOREIGN KEY (banking_product_id)
        REFERENCES deposit_banking_products (banking_product_id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_bdp_special_terms_term
        FOREIGN KEY (special_term_id)
        REFERENCES deposit_special_terms (special_term_id)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT uq_bdp_special_terms_product_term UNIQUE (banking_product_id, special_term_id)
);

-- =============================================
-- 수신 계약
-- =============================================
CREATE TABLE deposit_contracts (
    contract_id                     BIGSERIAL    PRIMARY KEY,
    contract_number                 VARCHAR(50)  NOT NULL UNIQUE,
    customer_id                     VARCHAR(30)  NOT NULL,
    banking_product_id              BIGINT       NOT NULL,
    is_monthly_payment              BOOLEAN      NOT NULL DEFAULT FALSE,
    payment_count_total             INT,
    monthly_payment_day             VARCHAR(6),
    join_amount                     NUMERIC(18,2) NOT NULL,
    contract_interest_rate          NUMERIC(5,2) NOT NULL,
    total_preferential_rate         NUMERIC(5,2) NOT NULL DEFAULT 0,
    final_interest_rate             NUMERIC(5,2) NOT NULL,
    tax_benefit_type                VARCHAR(30)  NOT NULL DEFAULT 'GENERAL',
    applied_tax_rate                NUMERIC(5,2) NOT NULL DEFAULT 15.40,
    expected_interest_amount        NUMERIC(18,2),
    contract_period_month           INT          NOT NULL,
    started_at                      CHAR(8)      NOT NULL,
    maturity_at                     CHAR(8),
    terminated_at                   CHAR(8),
    termination_reason              VARCHAR(200),
    is_auto_renewal                 BOOLEAN      NOT NULL DEFAULT FALSE,
    auto_transfer_enabled           BOOLEAN      NOT NULL DEFAULT FALSE,
    auto_transfer_day               INT,
    contract_status                 VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    status_changed_at               CHAR(8),
    join_channel                    VARCHAR(20)  NOT NULL,
    branch_id                       BIGINT,
    branch_code                     VARCHAR(20),
    branch_name                     VARCHAR(100),
    manager_id                      BIGINT,
    manager_name                    VARCHAR(100),
    is_proxy_joined                 BOOLEAN      NOT NULL DEFAULT FALSE,
    is_power_of_attorney_verified   BOOLEAN      NOT NULL DEFAULT FALSE,
    power_of_attorney_file_url      VARCHAR(500),
    terms_file_url                  VARCHAR(500),
    contract_file_url               VARCHAR(500),
    created_at                      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by                      VARCHAR(100),
    updated_at                      TIMESTAMPTZ,
    updated_by                      VARCHAR(100),
    CONSTRAINT fk_deposit_contracts_product
        FOREIGN KEY (banking_product_id)
        REFERENCES deposit_banking_products (banking_product_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
);

-- =============================================
-- 계약 우대 금리 적용 내역
-- =============================================
CREATE TABLE deposit_contract_applied_rates (
    applied_rate_id      BIGSERIAL    PRIMARY KEY,
    contract_id          BIGINT       NOT NULL,
    rate_id              BIGINT,
    applied_rate         NUMERIC(5,2) NOT NULL,
    condition_verified_yn BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by           VARCHAR(100),
    updated_at           TIMESTAMPTZ,
    updated_by           VARCHAR(100),
    CONSTRAINT fk_deposit_contract_applied_rates_contract
        FOREIGN KEY (contract_id)
        REFERENCES deposit_contracts (contract_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
);

-- =============================================
-- 수신 특약 동의
-- =============================================
CREATE TABLE deposit_contract_special_term_agreements (
    special_agreement_id      BIGSERIAL   PRIMARY KEY,
    contract_id               BIGINT      NOT NULL,
    special_term_id           BIGINT      NOT NULL,
    is_agreed                 BOOLEAN     NOT NULL,
    agreed_at                 CHAR(8),
    agreement_ip_address      VARCHAR(45),
    agreement_device_info     VARCHAR(255),
    is_electronic_signed      BOOLEAN     NOT NULL DEFAULT FALSE,
    is_agreement_withdrawn    BOOLEAN     NOT NULL DEFAULT FALSE,
    agreement_withdrawn_at    CHAR(8),
    created_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by                VARCHAR(100),
    updated_at                TIMESTAMPTZ,
    updated_by                VARCHAR(100),
    CONSTRAINT fk_dcsta_contract
        FOREIGN KEY (contract_id)
        REFERENCES deposit_contracts (contract_id)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_dcsta_special_term
        FOREIGN KEY (special_term_id)
        REFERENCES deposit_special_terms (special_term_id)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT uq_dcsta_contract_term UNIQUE (contract_id, special_term_id)
);

-- =============================================
-- 수신 계좌
-- =============================================
CREATE TABLE deposit_accounts (
    account_id                 BIGSERIAL    PRIMARY KEY,
    account_number             VARCHAR(30)  NOT NULL UNIQUE,
    customer_id                VARCHAR(30)  NOT NULL,
    contract_id                BIGINT       NOT NULL UNIQUE,
    account_type               VARCHAR(30)  NOT NULL,
    saving_type                VARCHAR(20),
    bank_code                  VARCHAR(10)  NOT NULL DEFAULT '001',
    account_alias              VARCHAR(100),
    balance                    NUMERIC(18,2) NOT NULL DEFAULT 0,
    total_paid_amount          NUMERIC(18,2) NOT NULL DEFAULT 0,
    total_interest_amount      NUMERIC(18,2) NOT NULL DEFAULT 0,
    last_transaction_at        TIMESTAMPTZ,
    last_interest_paid_at      TIMESTAMPTZ,
    currency                   CHAR(3)      NOT NULL DEFAULT 'KRW',
    account_password           VARCHAR(255) NOT NULL,
    daily_withdraw_limit       NUMERIC(18,2),
    daily_withdraw_count_limit INT,
    atm_withdraw_limit         NUMERIC(18,2),
    is_withdrawable            BOOLEAN      NOT NULL DEFAULT TRUE,
    is_online_banking_enabled  BOOLEAN      NOT NULL DEFAULT FALSE,
    is_mobile_banking_enabled  BOOLEAN      NOT NULL DEFAULT FALSE,
    is_phone_banking_enabled   BOOLEAN      NOT NULL DEFAULT FALSE,
    account_status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    opened_at                  CHAR(8)      NOT NULL,
    maturity_at                CHAR(8),
    dormant_at                 CHAR(8),
    dormant_released_at        CHAR(8),
    closed_at                  CHAR(8),
    status_changed_at          CHAR(8),
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by                 VARCHAR(100),
    updated_at                 TIMESTAMPTZ,
    updated_by                 VARCHAR(100),
    CONSTRAINT fk_deposit_accounts_contract
        FOREIGN KEY (contract_id)
        REFERENCES deposit_contracts (contract_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
);

-- =============================================
-- 이자 내역
-- =============================================
CREATE TABLE deposit_interest_history (
    interest_id                      BIGSERIAL    PRIMARY KEY,
    contract_id                      BIGINT       NOT NULL,
    account_id                       BIGINT       NOT NULL,
    applied_interest_rate            NUMERIC(5,2) NOT NULL,
    interest_calculation_start_date  CHAR(8),
    interest_calculation_end_date    CHAR(8),
    interest_occurred_at             TIMESTAMPTZ,
    interest_amount                  NUMERIC(18,2) NOT NULL,
    tax_benefit_type                 VARCHAR(30)  NOT NULL,
    applied_tax_rate                 NUMERIC(6,4) NOT NULL,
    interest_before_tax              NUMERIC(18,2) NOT NULL,
    interest_tax_amount              NUMERIC(18,2) NOT NULL DEFAULT 0,
    local_income_tax_amount          NUMERIC(18,2) NOT NULL DEFAULT 0,
    interest_after_tax               NUMERIC(18,2) NOT NULL,
    interest_reason                  VARCHAR(30)  NOT NULL,
    interest_paid_at                 TIMESTAMPTZ  NOT NULL,
    created_at                       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by                       VARCHAR(100),
    updated_at                       TIMESTAMPTZ,
    updated_by                       VARCHAR(100),
    CONSTRAINT fk_deposit_interest_history_contract
        FOREIGN KEY (contract_id)
        REFERENCES deposit_contracts (contract_id)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_deposit_interest_history_account
        FOREIGN KEY (account_id)
        REFERENCES deposit_accounts (account_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
);

-- =============================================
-- 청약 납입 인정 이력
-- =============================================
CREATE TABLE deposit_subscription_payment_recognition_history (
    recognition_id     BIGSERIAL    PRIMARY KEY,
    contract_id        BIGINT       NOT NULL,
    payment_amount     NUMERIC(18,2) NOT NULL,
    recognized_amount  NUMERIC(18,2) NOT NULL,
    payment_month      VARCHAR(6)   NOT NULL,
    recognized_at      TIMESTAMPTZ,
    recognition_status VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by         VARCHAR(100),
    updated_at         TIMESTAMPTZ,
    updated_by         VARCHAR(100),
    CONSTRAINT fk_dspr_history_contract
        FOREIGN KEY (contract_id)
        REFERENCES deposit_contracts (contract_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
);

-- =============================================
-- 거래 내역
-- =============================================
CREATE TABLE deposit_transactions (
    transaction_id          BIGSERIAL    PRIMARY KEY,
    transaction_number      VARCHAR(50)  NOT NULL UNIQUE,
    account_id              BIGINT       NOT NULL,
    contract_id             BIGINT,
    transaction_type        VARCHAR(30)  NOT NULL,
    direction_type          VARCHAR(10)  NOT NULL,
    amount                  NUMERIC(18,2) NOT NULL,
    balance_before          NUMERIC(18,2) NOT NULL,
    balance_after           NUMERIC(18,2) NOT NULL,
    available_balance_after NUMERIC(18,2),
    fee_amount              NUMERIC(18,2) NOT NULL DEFAULT 0,
    currency                CHAR(3)      NOT NULL DEFAULT 'KRW',
    status                  VARCHAR(20)  NOT NULL DEFAULT 'SUCCESS',
    channel_type            VARCHAR(30)  NOT NULL,
    ip_address              VARCHAR(45),
    terminal_id             VARCHAR(50),
    transaction_location    VARCHAR(100),
    transaction_memo        VARCHAR(255),
    transaction_summary     VARCHAR(100),
    transaction_at          TIMESTAMPTZ  NOT NULL,
    posted_at               TIMESTAMPTZ,
    canceled_at             TIMESTAMPTZ,
    depositor_customer_id   VARCHAR(30),
    depositor_name          VARCHAR(100),
    delegate_customer_id    VARCHAR(30),
    delegate_customer_name  VARCHAR(100),
    transfer_type           VARCHAR(30),
    counterparty_bank_code  VARCHAR(10),
    counterparty_bank_name  VARCHAR(100),
    counterparty_account_no VARCHAR(30),
    counterparty_account_id BIGINT,
    counterparty_customer_id VARCHAR(30),
    counterparty_name       VARCHAR(100),
    counterparty_name_verified_yn BOOLEAN,
    transfer_requested_at   TIMESTAMPTZ,
    transfer_completed_at   TIMESTAMPTZ,
    payment_method          VARCHAR(30),
    merchant_id             VARCHAR(50),
    merchant_name           VARCHAR(100),
    approval_number         VARCHAR(50),
    external_transaction_no VARCHAR(100),
    payment_round           INT,
    original_transaction_id BIGINT,
    failure_type            VARCHAR(30),
    failure_code            VARCHAR(50),
    failure_reason_code     VARCHAR(50),
    failure_at              TIMESTAMPTZ,
    retry_count             INTEGER      NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by              VARCHAR(100),
    updated_at              TIMESTAMPTZ,
    updated_by              VARCHAR(100),
    CONSTRAINT fk_deposit_transactions_account
        FOREIGN KEY (account_id)
        REFERENCES deposit_accounts (account_id)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_deposit_transactions_contract
        FOREIGN KEY (contract_id)
        REFERENCES deposit_contracts (contract_id)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_deposit_transactions_counterparty
        FOREIGN KEY (counterparty_account_id)
        REFERENCES deposit_accounts (account_id)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_deposit_transactions_original
        FOREIGN KEY (original_transaction_id)
        REFERENCES deposit_transactions (transaction_id)
        ON DELETE RESTRICT ON UPDATE CASCADE
);

-- =============================================
-- 인덱스
-- =============================================
CREATE INDEX idx_dbp_dept_status    ON deposit_banking_products (department_id, deposit_product_status);
CREATE INDEX idx_dbp_type_status    ON deposit_banking_products (deposit_product_type, deposit_product_status);
CREATE INDEX idx_bdp_product        ON banking_deposit_products (banking_product_id);
CREATE INDEX idx_dsp_product        ON deposit_savings_products (banking_product_id);
CREATE INDEX idx_bdp_ir_product     ON banking_deposit_product_interest_rates (banking_product_id, rate_type, is_active);
CREATE INDEX idx_bdp_ir_dates       ON banking_deposit_product_interest_rates (banking_product_id, effective_start_date, effective_end_date);
CREATE INDEX idx_bdp_tg_target      ON banking_deposit_product_target_groups (target_group_id);
CREATE INDEX idx_bdp_st_term        ON banking_deposit_product_special_terms (special_term_id);

CREATE INDEX idx_deposit_contracts_customer ON deposit_contracts (customer_id, contract_status);
CREATE INDEX idx_deposit_contracts_product  ON deposit_contracts (banking_product_id, contract_status);
CREATE INDEX idx_deposit_contracts_started  ON deposit_contracts (started_at);
CREATE INDEX idx_deposit_contracts_maturity ON deposit_contracts (maturity_at) WHERE contract_status = 'ACTIVE';
CREATE INDEX idx_deposit_car_contract       ON deposit_contract_applied_rates (contract_id);
CREATE INDEX idx_deposit_csta_contract      ON deposit_contract_special_term_agreements (contract_id);

CREATE INDEX idx_deposit_accounts_customer  ON deposit_accounts (customer_id, account_status);
CREATE INDEX idx_deposit_accounts_type      ON deposit_accounts (account_type, account_status);

CREATE INDEX idx_deposit_ih_contract        ON deposit_interest_history (contract_id, interest_paid_at DESC);
CREATE INDEX idx_deposit_ih_account         ON deposit_interest_history (account_id, interest_paid_at DESC);

CREATE INDEX idx_deposit_tx_account         ON deposit_transactions (account_id, transaction_at DESC);
CREATE INDEX idx_deposit_tx_contract        ON deposit_transactions (contract_id, transaction_at DESC) WHERE contract_id IS NOT NULL;
CREATE INDEX idx_deposit_tx_type_status     ON deposit_transactions (transaction_type, status, transaction_at DESC);

COMMIT;
