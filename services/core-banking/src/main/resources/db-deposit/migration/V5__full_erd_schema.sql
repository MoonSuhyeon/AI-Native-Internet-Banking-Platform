-- ERD 전체 스키마 (2025-05-20)
-- 공통·수신·여신·결제·챗봇 테이블 통합
-- 기존 V1 deposit_* 테이블과 병존 (충돌 없음)

BEGIN;

-- =============================================
-- 관계자 (party)
-- =============================================
CREATE TABLE party (
    party_id            BIGSERIAL       PRIMARY KEY,
    party_type_code     VARCHAR(20)     NOT NULL,
    party_name          VARCHAR(100)    NOT NULL,
    party_english_name  VARCHAR(200),
    party_status_code   VARCHAR(20)     NOT NULL,
    created_at          TIMESTAMPTZ(3)  NOT NULL,
    created_by          BIGINT,
    updated_at          TIMESTAMPTZ(3)  NOT NULL,
    updated_by          BIGINT,
    deleted_at          TIMESTAMPTZ(3),
    deleted_by          BIGINT
);

-- =============================================
-- 관계자 - 개인 (party_person)
-- =============================================
CREATE TABLE party_person (
    party_id                    BIGINT          NOT NULL,
    rrn_encrypted               VARCHAR(255),
    ci_value                    VARCHAR(88),
    nationality_type_code       VARCHAR(10),
    nationality_code            CHAR(3),
    birth_date                  CHAR(8),
    gender_code                 CHAR(1),
    marital_status_code         VARCHAR(10),
    dependent_count             INT,
    occupation_code             VARCHAR(10),
    occupation_name             VARCHAR(100),
    workplace_name              VARCHAR(200),
    annual_income_amount        BIGINT,
    income_proof_code           VARCHAR(10),
    capacity_limit_type_code    VARCHAR(20),
    is_pep_yn                   CHAR(1)         NOT NULL,
    pep_type_code               VARCHAR(10),
    pep_country_code            VARCHAR(3),
    pep_position                VARCHAR(200),
    death_date                  CHAR(8),
    created_at                  TIMESTAMPTZ(3)  NOT NULL,
    created_by                  BIGINT,
    updated_at                  TIMESTAMPTZ(3)  NOT NULL,
    updated_by                  BIGINT,
    deleted_at                  TIMESTAMPTZ(3),
    deleted_by                  BIGINT,
    PRIMARY KEY (party_id),
    CONSTRAINT fk_party_person_party
        FOREIGN KEY (party_id) REFERENCES party (party_id)
);

-- =============================================
-- 관계자 - 조직 (party_organization)
-- =============================================
CREATE TABLE party_organization (
    party_id                        BIGINT          NOT NULL,
    org_subtype_code                VARCHAR(20)     NOT NULL,
    corp_reg_no                     CHAR(14)        NOT NULL,
    corp_formal_name                VARCHAR(200)    NOT NULL,
    corp_formal_english_name        VARCHAR(400),
    hq_country_code                 CHAR(3),
    foreign_corp_reg_no_encrypted   VARCHAR(255)    NOT NULL,
    corp_type_code                  VARCHAR(20),
    non_corp_type_code              VARCHAR(10),
    ownership_type_code             VARCHAR(10),
    representative_type_code        VARCHAR(10),
    establishment_date              CHAR(8),
    dissolution_date                CHAR(8),
    capital_amount                  BIGINT,
    fiscal_month                    SMALLINT,
    establishment_purpose           VARCHAR(500),
    member_count                    INT,
    charter_url                     VARCHAR(500),
    created_at                      TIMESTAMPTZ(3)  NOT NULL,
    created_by                      BIGINT,
    updated_at                      TIMESTAMPTZ(3)  NOT NULL,
    updated_by                      BIGINT,
    deleted_at                      TIMESTAMPTZ(3),
    deleted_by                      BIGINT,
    PRIMARY KEY (party_id),
    CONSTRAINT fk_party_org_party
        FOREIGN KEY (party_id) REFERENCES party (party_id)
);

-- =============================================
-- 관계자 역할 (party_role)
-- =============================================
CREATE TABLE party_role (
    role_id                 BIGSERIAL       PRIMARY KEY,
    party_id                BIGINT          NOT NULL,
    role_type_code          VARCHAR(20)     NOT NULL,
    role_status_code        VARCHAR(20),
    role_start_date         CHAR(8),
    role_end_date           CHAR(8)         NOT NULL,
    role_end_reason_code    VARCHAR(20),
    created_at              TIMESTAMPTZ(3)  NOT NULL,
    created_by              BIGINT,
    updated_at              TIMESTAMPTZ(3)  NOT NULL,
    updated_by              BIGINT,
    deleted_at              TIMESTAMPTZ(3),
    deleted_by              BIGINT,
    CONSTRAINT fk_party_role_party
        FOREIGN KEY (party_id) REFERENCES party (party_id)
);

-- =============================================
-- 관계자 관계 (party_relation)
-- =============================================
CREATE TABLE party_relation (
    relation_id             BIGSERIAL       PRIMARY KEY,
    from_party_id           BIGINT          NOT NULL,
    to_party_id             BIGINT          NOT NULL,
    relation_type_code      VARCHAR(10)     NOT NULL,
    relation_detail_code    VARCHAR(10),
    equity_ratio_bps        INT,
    representation_scope    VARCHAR(200),
    proof_url               VARCHAR(500),
    relation_start_date     CHAR(8)         NOT NULL,
    relation_end_date       CHAR(8),
    relation_end_reason_code VARCHAR(20),
    created_at              TIMESTAMPTZ(3)  NOT NULL,
    created_by              BIGINT,
    updated_at              TIMESTAMPTZ(3)  NOT NULL,
    updated_by              BIGINT,
    deleted_at              TIMESTAMPTZ(3),
    deleted_by              BIGINT,
    CONSTRAINT fk_party_relation_from
        FOREIGN KEY (from_party_id) REFERENCES party (party_id),
    CONSTRAINT fk_party_relation_to
        FOREIGN KEY (to_party_id)   REFERENCES party (party_id)
);

-- =============================================
-- 고객 (customer)
-- =============================================
CREATE TABLE customer (
    customer_id                 BIGSERIAL       PRIMARY KEY,
    party_id                    BIGINT          NOT NULL,
    customer_grade_code         VARCHAR(10),
    customer_status_code        VARCHAR(20)     NOT NULL,
    main_customer_yn            CHAR(1),
    credit_rating_code          VARCHAR(10),
    credit_evaluation_date      CHAR(8),
    credit_agency_code          VARCHAR(10),
    preferred_language_code     CHAR(2),
    sms_receive_yn              CHAR(1),
    email_receive_yn            CHAR(1),
    postal_receive_yn           CHAR(1),
    notification_method_code    VARCHAR(10),
    email                       VARCHAR(255),
    phone                       VARCHAR(20),
    zip_code                    VARCHAR(10),
    address                     VARCHAR(255),
    address_detail              VARCHAR(255),
    join_channel_code           VARCHAR(20),
    first_join_date             CHAR(8),
    joined_at                   TIMESTAMPTZ(3)  NOT NULL,
    last_transaction_at         TIMESTAMPTZ(3),
    dormant_datetime            TIMESTAMPTZ(3),
    closed_at                   TIMESTAMPTZ(3),
    close_reason_code           VARCHAR(20),
    privacy_expiry_date         CHAR(8),
    created_at                  TIMESTAMPTZ(3)  NOT NULL,
    created_by                  BIGINT,
    updated_at                  TIMESTAMPTZ(3)  NOT NULL,
    updated_by                  BIGINT,
    deleted_at                  TIMESTAMPTZ(3),
    deleted_by                  BIGINT,
    CONSTRAINT fk_customer_party
        FOREIGN KEY (party_id) REFERENCES party (party_id)
);

-- =============================================
-- 공통 상품 (common_product)
-- =============================================
CREATE TABLE common_product (
    product_id                  BIGSERIAL       PRIMARY KEY,
    product_cd                  VARCHAR(30)     NOT NULL UNIQUE,
    biz_div_cd                  VARCHAR(10)     NOT NULL,
    product_name                VARCHAR(200)    NOT NULL,
    product_type_cd             VARCHAR(20),
    product_description         TEXT,
    target_type_cd              VARCHAR(50),
    channel_cd                  VARCHAR(50),
    currency_cd                 VARCHAR(50),
    policy_product_yn           CHAR(1),
    min_amount                  BIGINT,
    max_amount                  BIGINT,
    min_period_mo               INT,
    max_period_mo               INT,
    sale_yn                     CHAR(1),
    sale_start_date             CHAR(8),
    sale_end_date               CHAR(8),
    product_brochure_url        VARCHAR(500),
    financial_consumer_act_yn   CHAR(1),
    product_status              VARCHAR(50),
    created_by                  BIGINT,
    created_at                  TIMESTAMPTZ(3),
    updated_by                  BIGINT,
    updated_at                  TIMESTAMPTZ(3)
);

-- =============================================
-- 수신 상품 (deposit_product)  PK = FK
-- =============================================
CREATE TABLE deposit_product (
    product_id                      BIGINT          NOT NULL,
    doduct_tyeposit_prpe            VARCHAR(30),
    department_id                   BIGINT,
    base_interest_rate              NUMERIC(5,2),
    preferential_rate_condition     TEXT,
    is_early_termination_allowed    CHAR(1),
    is_tax_benefit_available        CHAR(1),
    is_auto_renewal_available       CHAR(1),
    is_passbook_issued              CHAR(1),
    PRIMARY KEY (product_id),
    CONSTRAINT fk_deposit_product_common
        FOREIGN KEY (product_id) REFERENCES common_product (product_id)
);

-- =============================================
-- 대출 상품 (loan_product)  PK = FK
-- =============================================
CREATE TABLE loan_product (
    product_id                  BIGINT          NOT NULL,
    loan_purpose_cd             VARCHAR(50),
    collateral_type_cd          VARCHAR(50),
    repayment_methods_cd        VARCHAR(500),
    guarantee_type_cd           VARCHAR(50),
    rate_type_cd                VARCHAR(50),
    display_min_rate_bps        INT,
    display_max_rate_bps        INT,
    collateral_required_yn      CHAR(1),
    guarantee_required_yn       CHAR(1),
    early_repay_fee_yn          CHAR(1),
    holiday_repay_target_yn     CHAR(1),
    loan_product_status_cd      VARCHAR(20),
    created_at                  TIMESTAMPTZ(3)  NOT NULL,
    created_by                  BIGINT,
    updated_at                  TIMESTAMPTZ(3),
    updated_by                  BIGINT,
    deleted_at                  TIMESTAMPTZ(3),
    deleted_by                  BIGINT,
    PRIMARY KEY (product_id),
    CONSTRAINT fk_loan_product_common
        FOREIGN KEY (product_id) REFERENCES common_product (product_id)
);

-- =============================================
-- 공통 약관 템플릿 (common_terms_template)
-- =============================================
CREATE TABLE common_terms_template (
    terms_template_id   BIGSERIAL       PRIMARY KEY,
    terms_no            VARCHAR(50)     NOT NULL UNIQUE,
    terms_name          VARCHAR(200)    NOT NULL,
    terms_category_cd   VARCHAR(10)     NOT NULL,
    description         TEXT,
    required_yn         CHAR(1)         NOT NULL DEFAULT 'Y',
    biz_div_cd          VARCHAR(50)     NOT NULL,
    active_yn           CHAR(1)         NOT NULL DEFAULT 'Y',
    created_at          TIMESTAMPTZ(3)  NOT NULL,
    created_by          BIGINT          NOT NULL,
    updated_at          TIMESTAMPTZ(3)  NOT NULL,
    updated_by          BIGINT          NOT NULL,
    deleted_at          TIMESTAMPTZ(3),
    deleted_by          BIGINT
);

-- =============================================
-- 약관 적용 관리 (terms_target_map)
-- =============================================
CREATE TABLE terms_target_map (
    terms_target_map_id BIGSERIAL       PRIMARY KEY,
    terms_template_id   BIGINT          NOT NULL,
    target_id           BIGINT,
    biz_div_cd          VARCHAR(10),
    required_yn         CHAR(1),
    created_by          BIGINT,
    created_at          TIMESTAMPTZ(3),
    updated_by          BIGINT,
    updated_at          TIMESTAMPTZ(3),
    CONSTRAINT fk_terms_target_map_template
        FOREIGN KEY (terms_template_id) REFERENCES common_terms_template (terms_template_id)
);

-- =============================================
-- 인증 토큰 (Korean physical names)
-- =============================================
CREATE TABLE "인증토큰" (
    "인증토큰번호"  VARCHAR(50)  NOT NULL,
    "고객ID"        BIGINT       NOT NULL,
    "인증유형"      VARCHAR(20)  NOT NULL,
    "최초등록일시"  TIMESTAMP    NOT NULL,
    "최초등록자ID"  BIGINT,
    "최종수정일시"  TIMESTAMP    NOT NULL,
    "최종수정자ID"  BIGINT,
    PRIMARY KEY ("인증토큰번호"),
    CONSTRAINT fk_auth_token_customer
        FOREIGN KEY ("고객ID") REFERENCES customer (customer_id)
);

-- =============================================
-- 결제 지시 (Korean physical names)
-- =============================================
CREATE TABLE "결제지시" (
    "결제지시번호"          VARCHAR(20)     NOT NULL,
    "연결된멱등키"          VARCHAR(50)     NOT NULL,
    "송신고객번호"          VARCHAR(30),
    "송신계좌번호"          VARCHAR(30),
    "인증토큰번호"          VARCHAR(50),
    "원거래참조번호"        VARCHAR(20),
    "거래번호"              VARCHAR(30)     NOT NULL,
    "송신계좌번호_스냅샷"   VARCHAR(30)     NOT NULL,
    "송신계좌별명_스냅샷"   VARCHAR(60),
    "수신은행코드"          CHAR(3)         NOT NULL,
    "수신계좌번호"          VARCHAR(30)     NOT NULL,
    "수신예금주명_스냅샷"   VARCHAR(60)     NOT NULL,
    "예금주조회일시"        TIMESTAMP       NOT NULL,
    "자행이체여부"          CHAR(1)         NOT NULL,
    "라우팅망종류"          VARCHAR(20)     NOT NULL,
    "이체금액"              NUMERIC(15,0)   NOT NULL,
    "결제수수료"            NUMERIC(15,0)   NOT NULL,
    "수신통장_송신자표시명" VARCHAR(60),
    "받는분통장메모"        VARCHAR(100),
    "내통장메모"            VARCHAR(100),
    "진행상태"              VARCHAR(20)     NOT NULL,
    "실패분류"              VARCHAR(30),
    "결제지시채널"          VARCHAR(20)     NOT NULL,
    "요청일시"              TIMESTAMP       NOT NULL,
    "완료일시"              TIMESTAMP,
    "영업일자"              CHAR(8)         NOT NULL,
    "다음재시도일시"        TIMESTAMP,
    "다음타임아웃일시"      TIMESTAMP,
    "트리거주체"            VARCHAR(20)     NOT NULL,
    "예약여부"              CHAR(1)         NOT NULL,
    "예약실행일시"          TIMESTAMP,
    "최초등록일시"          TIMESTAMP       NOT NULL,
    "최초등록자ID"          BIGINT,
    "최종수정일시"          TIMESTAMP       NOT NULL,
    "최종수정자ID"          BIGINT,
    PRIMARY KEY ("결제지시번호"),
    CONSTRAINT fk_payment_instr_token
        FOREIGN KEY ("인증토큰번호") REFERENCES "인증토큰" ("인증토큰번호")
);

-- =============================================
-- 원장 (Korean physical names)
-- =============================================
CREATE TABLE "원장" (
    "원장번호"                VARCHAR(20)     NOT NULL,
    "결제지시번호"            VARCHAR(20),
    "계좌번호"                VARCHAR(30)     NOT NULL,
    "원분개참조번호"          VARCHAR(20),
    "회계번호"                VARCHAR(20)     NOT NULL,
    "계좌번호_스냅샷"         VARCHAR(30)     NOT NULL,
    "예금주명_스냅샷"         VARCHAR(60)     NOT NULL,
    "차변대변구분"            CHAR(6)         NOT NULL,
    "분개종류"                VARCHAR(30)     NOT NULL,
    "금액"                    NUMERIC(15,0)   NOT NULL,
    "통화"                    CHAR(3)         NOT NULL,
    "분개직전잔액"            NUMERIC(15,0)   NOT NULL,
    "분개직후잔액"            NUMERIC(15,0)   NOT NULL,
    "상대계좌번호_스냅샷"     VARCHAR(30),
    "상대은행코드_스냅샷"     CHAR(3),
    "상대예금주명_스냅샷"     VARCHAR(60),
    "거래일자"                CHAR(8)         NOT NULL,
    "기장일자"                CHAR(8)         NOT NULL,
    "자금가용일"              CHAR(8)         NOT NULL,
    "기장일시"                TIMESTAMP       NOT NULL,
    "시스템적요"              VARCHAR(100)    NOT NULL,
    "통장에찍히는메모_스냅샷" VARCHAR(100),
    "역분개여부"              CHAR(1)         NOT NULL,
    "역분개사유"              VARCHAR(20),
    "기장상태"                VARCHAR(20)     NOT NULL,
    "최초등록일시"            TIMESTAMP       NOT NULL,
    "최초등록자ID"            BIGINT,
    "최종수정일시"            TIMESTAMP       NOT NULL,
    "최종수정자ID"            BIGINT,
    PRIMARY KEY ("원장번호"),
    CONSTRAINT fk_ledger_payment
        FOREIGN KEY ("결제지시번호") REFERENCES "결제지시" ("결제지시번호")
);

-- =============================================
-- 상태 이력 (Korean physical names)
-- =============================================
CREATE TABLE "상태이력" (
    "상태이력번호"    VARCHAR(20)  NOT NULL,
    "결제지시번호"    VARCHAR(20)  NOT NULL,
    "결제지시내순번"  INT          NOT NULL,
    "이전상태"        VARCHAR(20),
    "다음상태"        VARCHAR(20)  NOT NULL,
    "이벤트종류"      VARCHAR(30)  NOT NULL,
    "사유코드"        VARCHAR(10),
    "사유메시지"      VARCHAR(200),
    "트리거주체"      VARCHAR(20)  NOT NULL,
    "운영자ID"        VARCHAR(20),
    "이벤트발생일시"  TIMESTAMP    NOT NULL,
    "최초등록일시"    TIMESTAMP    NOT NULL,
    "최초등록자ID"    BIGINT,
    "최종수정일시"    TIMESTAMP    NOT NULL,
    "최종수정자ID"    BIGINT,
    PRIMARY KEY ("상태이력번호"),
    CONSTRAINT fk_status_history_payment
        FOREIGN KEY ("결제지시번호") REFERENCES "결제지시" ("결제지시번호")
);

-- =============================================
-- 금융결제원 청산 거래 (Korean physical names)
-- =============================================
CREATE TABLE "금융결제원청산거래" (
    "청산거래번호"          VARCHAR(20)     NOT NULL,
    "우리결제지시번호"      VARCHAR(20)     NOT NULL,
    "거래방향"              VARCHAR(5)      NOT NULL,
    "상대은행결제지시번호"  VARCHAR(50),
    "청산번호"              VARCHAR(50)     NOT NULL,
    "송신은행의청산ID"      VARCHAR(50),
    "수신은행의청산ID"      VARCHAR(50),
    "송신은행코드"          CHAR(3)         NOT NULL,
    "송신계좌번호_스냅샷"   VARCHAR(30)     NOT NULL,
    "송신예금주명_스냅샷"   VARCHAR(60)     NOT NULL,
    "수신은행코드"          CHAR(3)         NOT NULL,
    "수신계좌번호_스냅샷"   VARCHAR(30)     NOT NULL,
    "수신예금주명_스냅샷"   VARCHAR(60)     NOT NULL,
    "청산금액"              NUMERIC(15,0)   NOT NULL,
    "통화"                  CHAR(3)         NOT NULL,
    "청산상태"              VARCHAR(20)     NOT NULL,
    "거절코드"              VARCHAR(10),
    "거절메시지"            VARCHAR(200),
    "청산요청일시"          TIMESTAMP       NOT NULL,
    "ACK수신일시"           TIMESTAMP,
    "정산완료일시"          TIMESTAMP,
    "정산일자"              CHAR(8),
    "네트워크"              VARCHAR(30)     NOT NULL,
    "조회횟수"              INT             NOT NULL,
    "마지막조회일시"        TIMESTAMP,
    "최초등록일시"          TIMESTAMP       NOT NULL,
    "최초등록자ID"          BIGINT,
    "최종수정일시"          TIMESTAMP       NOT NULL,
    "최종수정자ID"          BIGINT,
    PRIMARY KEY ("청산거래번호"),
    CONSTRAINT fk_clearing_tx_payment
        FOREIGN KEY ("우리결제지시번호") REFERENCES "결제지시" ("결제지시번호")
);

-- =============================================
-- 한국은행 결제 거래 (Korean physical names)
-- =============================================
CREATE TABLE "한국은행결제거래" (
    "결제거래번호"              VARCHAR(20)     NOT NULL,
    "우리결제지시번호"          VARCHAR(20)     NOT NULL,
    "거래방향"                  VARCHAR(5)      NOT NULL,
    "상대은행결제지시번호"      VARCHAR(50),
    "한은망참조번호"            VARCHAR(50)     NOT NULL,
    "송신은행코드"              CHAR(3)         NOT NULL,
    "송신은행의결제지시번호"    VARCHAR(50)     NOT NULL,
    "송신계좌번호_스냅샷"       VARCHAR(30)     NOT NULL,
    "송신예금주명_스냅샷"       VARCHAR(60)     NOT NULL,
    "수신은행코드"              CHAR(3)         NOT NULL,
    "수신은행의결제지시번호"    VARCHAR(50),
    "수신계좌번호_스냅샷"       VARCHAR(30)     NOT NULL,
    "수신예금주명_스냅샷"       VARCHAR(60)     NOT NULL,
    "결제금액"                  NUMERIC(15,0)   NOT NULL,
    "통화"                      CHAR(3)         NOT NULL,
    "결제상태"                  VARCHAR(20)     NOT NULL,
    "거절코드"                  VARCHAR(10),
    "거절메시지"                VARCHAR(200),
    "결제요청일시"              TIMESTAMP       NOT NULL,
    "ACK수신일시"               TIMESTAMP,
    "결제확정일시"              TIMESTAMP,
    "영업일자"                  CHAR(8)         NOT NULL,
    "조회횟수"                  INT             NOT NULL,
    "마지막조회일시"            TIMESTAMP,
    "최초등록일시"              TIMESTAMP       NOT NULL,
    "최초등록자ID"              BIGINT,
    "최종수정일시"              TIMESTAMP       NOT NULL,
    "최종수정자ID"              BIGINT,
    PRIMARY KEY ("결제거래번호"),
    CONSTRAINT fk_bok_payment_instr
        FOREIGN KEY ("우리결제지시번호") REFERENCES "결제지시" ("결제지시번호")
);

-- =============================================
-- 공통 계약 (common_contract)
-- =============================================
CREATE TABLE common_contract (
    contract_id                 BIGSERIAL       PRIMARY KEY,
    contract_no                 VARCHAR(50),
    customer_id                 BIGINT          NOT NULL,
    customer_no                 VARCHAR(30),
    product_id                  BIGINT          NOT NULL,
    biz_div_cd                  VARCHAR(10),
    contract_amount             BIGINT,
    rate_type_cd                VARCHAR(10),
    base_rate_bps               VARCHAR(10),
    spread_bps                  INT,
    preferential_bps            INT,
    total_rate_bps              INT,
    interest_amount_at_maturity BIGINT,
    contract                    INT,
    contract_start_date         CHAR(8),
    contract_end_date           CHAR(8),
    contract_cancel_date        CHAR(8),
    contract_cancel_reason      VARCHAR(200),
    auto_transfer_yn            CHAR(1),
    auto_transfer_day           INT,
    signed_at                   TIMESTAMPTZ(3),
    contract_channel_cd         VARCHAR(20),
    spot_id                     BIGINT,
    spot_name                   VARCHAR(100),
    manager_id                  BIGINT,
    manager_name                VARCHAR(100),
    proxy_yn                    CHAR(1),
    contract_status             VARCHAR(20),
    term_url                    VARCHAR(500),
    term_hash                   VARCHAR(64),
    contract_url                VARCHAR(500),
    contract_hash               VARCHAR(64),
    created_at                  TIMESTAMPTZ(3),
    created_by                  BIGINT,
    updated_at                  TIMESTAMPTZ(3),
    updated_by                  BIGINT,
    CONSTRAINT fk_common_contract_customer
        FOREIGN KEY (customer_id) REFERENCES customer (customer_id),
    CONSTRAINT fk_common_contract_product
        FOREIGN KEY (product_id)  REFERENCES common_product (product_id)
);

-- =============================================
-- 수신 계약 (deposit_contract)  PK = FK
-- =============================================
CREATE TABLE deposit_contract (
    contract_id                     BIGINT          NOT NULL,
    is_monthly_payment              CHAR(1),
    payment_count_total             INT,
    contract_interest_rate          NUMERIC(5,2),
    total_preferential_rate         NUMERIC(5,2),
    final_interest_rate             NUMERIC(5,2),
    tax_benefit_type                VARCHAR(30),
    applied_tax_rate                NUMERIC(5,2),
    expected_interest_amount        NUMERIC(18,2),
    is_auto_renewal                 CHAR(1),
    status_changed_at               CHAR(8),
    "계약 지점 코드"                VARCHAR(20),
    is_power_of_attorney_verified   CHAR(1),
    power_of_attorney_file_url      VARCHAR(500),
    PRIMARY KEY (contract_id),
    CONSTRAINT fk_deposit_contract_common
        FOREIGN KEY (contract_id) REFERENCES common_contract (contract_id)
);

-- =============================================
-- 공통 약관 동의 (common_terms_consent)
-- =============================================
CREATE TABLE common_terms_consent (
    consent_id          BIGSERIAL       NOT NULL,
    customer_id         BIGINT          NOT NULL,
    terms_template_id   BIGINT          NOT NULL,
    biz_div_cd          VARCHAR(10)     NOT NULL,
    consent_target_id   BIGINT,
    consent_status_cd   VARCHAR(10)     NOT NULL,
    agreed_yn           CHAR(1)         NOT NULL,
    agreed_at           CHAR(8)         NOT NULL,
    consent_method_cd   VARCHAR(10)     NOT NULL,
    consent_tool        VARCHAR(500),
    signed_doc_url      VARCHAR(500),
    signed_doc_hash     VARCHAR(64),
    client_ip           INET,
    withdrawn_yn        CHAR(1)         NOT NULL DEFAULT 'N',
    withdrawn_at        TIMESTAMPTZ(3),
    withdrawn_reason    VARCHAR(500),
    retention_until     VARCHAR(8),
    created_at          TIMESTAMPTZ(3)  NOT NULL,
    created_by          BIGINT          NOT NULL,
    updated_at          TIMESTAMPTZ(3)  NOT NULL,
    updated_by          BIGINT          NOT NULL,
    deleted_at          TIMESTAMPTZ(3),
    deleted_by          BIGINT,
    PRIMARY KEY (consent_id, customer_id),
    CONSTRAINT fk_terms_consent_customer
        FOREIGN KEY (customer_id)       REFERENCES customer (customer_id),
    CONSTRAINT fk_terms_consent_template
        FOREIGN KEY (terms_template_id) REFERENCES common_terms_template (terms_template_id)
);

-- =============================================
-- 대출 신청 (loan_application)
-- =============================================
CREATE TABLE loan_application (
    application_id              BIGSERIAL       PRIMARY KEY,
    application_no              VARCHAR(30)     NOT NULL,
    customer_id                 BIGINT          NOT NULL,
    product_id                  BIGINT          NOT NULL,
    apply_channel_cd            VARCHAR(20)     NOT NULL,
    application_branch_id       BIGINT,
    application_charge_id       BIGINT,
    request_amount              BIGINT          NOT NULL,
    request_period_mo           INT             NOT NULL,
    purpose_cd                  VARCHAR(20)     NOT NULL,
    repayment_method_cd         VARCHAR(20)     NOT NULL,
    desired_disbursement_date   CHAR(8),
    reject_reason               VARCHAR(500),
    cancel_reason               VARCHAR(500),
    applied_at                  CHAR(8)         NOT NULL,
    completed_at                CHAR(8),
    apply_status                VARCHAR(20)     NOT NULL,
    created_at                  TIMESTAMPTZ(3)  NOT NULL,
    created_by                  BIGINT          NOT NULL,
    updated_at                  TIMESTAMPTZ(3)  NOT NULL,
    updated_by                  BIGINT          NOT NULL,
    deleted_at                  TIMESTAMPTZ(3),
    deleted_by                  BIGINT,
    CONSTRAINT fk_loan_app_customer
        FOREIGN KEY (customer_id) REFERENCES customer (customer_id),
    CONSTRAINT fk_loan_app_product
        FOREIGN KEY (product_id)  REFERENCES common_product (product_id)
);

-- =============================================
-- 대출 심사 (loan_review)
-- 자기참조 FK (previous_review_id) → DEFERRABLE
-- =============================================
CREATE TABLE loan_review (
    loan_review_id              BIGSERIAL       PRIMARY KEY,
    loan_review_no              VARCHAR(50)     NOT NULL,
    application_id              BIGINT          NOT NULL,
    product_id                  BIGINT          NOT NULL,
    review_target_cd            VARCHAR(30),
    review_round                INT,
    approved_amount             BIGINT,
    approved_rate_bps           INT,
    review_method_cd            VARCHAR(30),
    reviewer_id                 BIGINT,
    approver_id                 BIGINT,
    loan_review_round_cd        VARCHAR(50),
    review_opinion_cd           VARCHAR(30),
    review_opinion_reason_cd    VARCHAR(30),
    approval_decision_cd        VARCHAR(30),
    approval_decision_reason_cd VARCHAR(30),
    assigned_at                 CHAR(8),
    started_at                  CHAR(8),
    decided_at                  CHAR(8),
    previous_review_id          BIGINT          NOT NULL,
    review_status_cd            VARCHAR(30),
    created_at                  TIMESTAMPTZ(3),
    created_by                  BIGINT,
    updated_at                  TIMESTAMPTZ(3),
    updated_by                  BIGINT,
    deleted_at                  TIMESTAMPTZ(3),
    deleted_by                  BIGINT,
    CONSTRAINT fk_loan_review_application
        FOREIGN KEY (application_id)     REFERENCES loan_application (application_id),
    CONSTRAINT fk_loan_review_product
        FOREIGN KEY (product_id)         REFERENCES common_product (product_id),
    CONSTRAINT fk_loan_review_previous
        FOREIGN KEY (previous_review_id) REFERENCES loan_review (loan_review_id)
        DEFERRABLE INITIALLY DEFERRED
);

-- =============================================
-- 공통 계좌 (common_account)
-- =============================================
CREATE TABLE common_account (
    account_id              BIGSERIAL       PRIMARY KEY,
    account_no              VARCHAR(30)     UNIQUE,
    customer_id             BIGINT          NOT NULL,
    customer_no             VARCHAR(30),
    contract_id             BIGINT,
    account_type_cd         VARCHAR(30),
    bank_cd                 VARCHAR(10),
    account_nickname        VARCHAR(100),
    balance                 BIGINT,
    currency_cd             CHAR(3),
    account_password_hash   VARCHAR(255),
    daily_withdrawal_limit  BIGINT,
    daily_withdrawal_count  INT,
    suspic_account_yn       CHAR(1),
    account_status          VARCHAR(20),
    account_opened_at       CHAR(8),
    account_closed_at       CHAR(8),
    account_cancel_at       TIMESTAMPTZ(3),
    last_transaction_at     TIMESTAMPTZ(3),
    created_at              TIMESTAMPTZ(3),
    created_by              BIGINT,
    updated_at              TIMESTAMPTZ(3),
    updated_by              BIGINT,
    CONSTRAINT fk_common_account_customer
        FOREIGN KEY (customer_id)  REFERENCES customer (customer_id),
    CONSTRAINT fk_common_account_contract
        FOREIGN KEY (contract_id)  REFERENCES common_contract (contract_id)
);

-- =============================================
-- 수신 계좌 (deposit_account)  PK = FK
-- =============================================
CREATE TABLE deposit_account (
    account_id                  BIGINT          NOT NULL,
    saving_type                 VARCHAR(20),
    balance                     NUMERIC(18,2),
    total_paid_amount           NUMERIC(18,2),
    total_interest_amount       NUMERIC(18,2),
    last_transaction_at         CHAR(8),
    last_interest_paid_at       CHAR(8),
    is_withdrawable             CHAR(1),
    daily_withdraw_limit        NUMERIC(18,2),
    daily_withdraw_count_limit  INT,
    atm_withdraw_limit          NUMERIC(18,2),
    is_online_banking_enabled   CHAR(1),
    is_mobile_banking_enabled   CHAR(1),
    is_phone_banking_enabled    CHAR(1),
    dormant_at                  CHAR(8),
    dormant_released_at         CHAR(8),
    status_changed_at           CHAR(8),
    PRIMARY KEY (account_id),
    CONSTRAINT fk_deposit_account_common
        FOREIGN KEY (account_id) REFERENCES common_account (account_id)
);

-- =============================================
-- 여신 계약 (loan_contract)  PK = FK
-- ERD 그대로: updated_by TIMESTAMPTZ, updated_ BIGINT
-- =============================================
CREATE TABLE loan_contract (
    contract_id               BIGINT          NOT NULL,
    product_id                BIGINT          NOT NULL,
    loan_review_id            BIGINT          NOT NULL,
    loan_contract_no          VARCHAR(30)     NOT NULL,
    loan_application_id       BIGINT          NOT NULL,
    loan_product_name         VARCHAR(30),
    contractor_id             BIGINT          NOT NULL,
    contractor_name           VARCHAR(30),
    facility_type_cd          VARCHAR(20)     NOT NULL,
    repayment_method_cd       VARCHAR(20)     NOT NULL,
    allocation_policy_cd      VARCHAR(30)     NOT NULL,
    prepayment_recalc_mode_cd VARCHAR(30)     NOT NULL,
    created_at                TIMESTAMPTZ(3)  NOT NULL,
    created_by                BIGINT          NOT NULL,
    updated_by                TIMESTAMPTZ(3)  NOT NULL,
    updated_                  BIGINT          NOT NULL,
    deleted_at                TIMESTAMPTZ(3),
    deleted_by                BIGINT,
    PRIMARY KEY (contract_id),
    CONSTRAINT fk_loan_contract_common
        FOREIGN KEY (contract_id)        REFERENCES common_contract (contract_id),
    CONSTRAINT fk_loan_contract_product
        FOREIGN KEY (product_id)         REFERENCES loan_product (product_id),
    CONSTRAINT fk_loan_contract_review
        FOREIGN KEY (loan_review_id)     REFERENCES loan_review (loan_review_id),
    CONSTRAINT fk_loan_contract_application
        FOREIGN KEY (loan_application_id) REFERENCES loan_application (application_id)
);

-- =============================================
-- 여신 계좌 (loan_account)  PK = FK
-- ERD 그대로: product_id3, contract_id2
-- =============================================
CREATE TABLE loan_account (
    account_id           BIGINT          NOT NULL,
    customer_id          BIGINT          NOT NULL,
    product_id3          BIGINT          NOT NULL,
    contract_id2         BIGINT          NOT NULL,
    loan_account_type_cd VARCHAR(20)     NOT NULL,
    purpose_cd           VARCHAR(20),
    unpaid_interest      BIGINT          NOT NULL,
    principal_balance    BIGINT          NOT NULL,
    loan_balance         BIGINT          NOT NULL,
    valid_from           CHAR(8),
    valid_to             CHAR(8),
    loan_account_status  VARCHAR(20)     NOT NULL,
    created_at           TIMESTAMPTZ(3)  NOT NULL,
    created_by           BIGINT          NOT NULL,
    updated_at           TIMESTAMPTZ(3)  NOT NULL,
    updated_by           BIGINT          NOT NULL,
    deleted_at           TIMESTAMPTZ(3),
    deleted_by           BIGINT,
    PRIMARY KEY (account_id),
    CONSTRAINT fk_loan_account_common
        FOREIGN KEY (account_id)   REFERENCES common_account (account_id),
    CONSTRAINT fk_loan_account_customer
        FOREIGN KEY (customer_id)  REFERENCES customer (customer_id),
    CONSTRAINT fk_loan_account_product
        FOREIGN KEY (product_id3)  REFERENCES common_product (product_id),
    CONSTRAINT fk_loan_account_contract
        FOREIGN KEY (contract_id2) REFERENCES common_contract (contract_id)
);

-- =============================================
-- 대출 실행 (loan_execution)
-- 자기참조 2개 → DEFERRABLE
-- =============================================
CREATE TABLE loan_execution (
    execution_id                    BIGSERIAL       PRIMARY KEY,
    customer_id                     BIGINT          NOT NULL,
    contract_id                     BIGINT          NOT NULL,
    disbursement_account_id         BIGINT          NOT NULL,
    payment_tx_id                   VARCHAR(50),
    disbursement_amount             BIGINT          NOT NULL,
    masked_account_no               VARCHAR(30),
    tranche_no                      INT             NOT NULL,
    before_execution_id             BIGINT          NOT NULL,
    idempotency_key                 VARCHAR(100)    NOT NULL,
    loan_exection_status_cd         VARCHAR(20)     NOT NULL,
    loan_exection_fail_reason_cd    VARCHAR(30),
    loan_exection_fail_reason_detail TEXT,
    requested_at                    CHAR(8)         NOT NULL,
    executed_at                     CHAR(8),
    reversal_yn                     CHAR(1)         NOT NULL,
    reverses_execution_id           BIGINT          NOT NULL,
    reversal_reason_cd              VARCHAR(30),
    reversal_at                     TIMESTAMPTZ(3),
    created_at                      TIMESTAMPTZ(3)  NOT NULL,
    created_by                      BIGINT          NOT NULL,
    updated_at                      TIMESTAMPTZ(3)  NOT NULL,
    updated_by                      BIGINT          NOT NULL,
    deleted_at                      TIMESTAMPTZ(3),
    deleted_by                      BIGINT,
    CONSTRAINT fk_loan_exec_customer
        FOREIGN KEY (customer_id)           REFERENCES customer (customer_id),
    CONSTRAINT fk_loan_exec_contract
        FOREIGN KEY (contract_id)           REFERENCES common_contract (contract_id),
    CONSTRAINT fk_loan_exec_account
        FOREIGN KEY (disbursement_account_id) REFERENCES common_account (account_id),
    CONSTRAINT fk_loan_exec_before
        FOREIGN KEY (before_execution_id)   REFERENCES loan_execution (execution_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_loan_exec_reverses
        FOREIGN KEY (reverses_execution_id) REFERENCES loan_execution (execution_id)
        DEFERRABLE INITIALLY DEFERRED
);

-- =============================================
-- 대출 상환 (loan_repayment)
-- 자기참조 2개 → DEFERRABLE
-- =============================================
CREATE TABLE loan_repayment (
    repayment_id                        BIGSERIAL       PRIMARY KEY,
    party_id                            BIGINT          NOT NULL,
    contract_id                         BIGINT          NOT NULL,
    tx_type_cd                          VARCHAR(20)     NOT NULL,
    payment_channel_cd                  VARCHAR(20)     NOT NULL,
    idempotency_key                     VARCHAR(100)    NOT NULL,
    payment_tx_id                       VARCHAR(50),
    paid_principal                      BIGINT          NOT NULL,
    paid_interest                       BIGINT          NOT NULL,
    paid_late_fee                       BIGINT          NOT NULL,
    total_paid                          BIGINT          NOT NULL,
    loan_account_id                     BIGINT          NOT NULL,
    virtual_account_no                  VARCHAR(30),
    sender_name                         VARCHAR(100),
    third_party_yn                      CHAR(1)         NOT NULL,
    loan_repayment_status               VARCHAR(20)     NOT NULL,
    loan_repayment_fail_reason_cd       VARCHAR(30),
    loan_repayment_fail_reason_detail   TEXT,
    previous_repayment_id               BIGINT          NOT NULL,
    reverses_repayment_id               BIGINT          NOT NULL,
    reversal_yn                         CHAR(1)         NOT NULL,
    reversal_reason_cd                  VARCHAR(30),
    reversal_at                         TIMESTAMPTZ(3),
    requested_at                        CHAR(8)         NOT NULL,
    paid_at                             CHAR(8),
    value_date                          CHAR(8)         NOT NULL,
    created_at                          TIMESTAMPTZ(3)  NOT NULL,
    created_by                          BIGINT          NOT NULL,
    updated_at                          TIMESTAMPTZ(3)  NOT NULL,
    updated_by                          BIGINT          NOT NULL,
    deleted_at                          TIMESTAMPTZ(3),
    deleted_by                          BIGINT,
    CONSTRAINT fk_loan_repay_party
        FOREIGN KEY (party_id)              REFERENCES party (party_id),
    CONSTRAINT fk_loan_repay_contract
        FOREIGN KEY (contract_id)           REFERENCES common_contract (contract_id),
    CONSTRAINT fk_loan_repay_account
        FOREIGN KEY (loan_account_id)       REFERENCES loan_account (account_id),
    CONSTRAINT fk_loan_repay_previous
        FOREIGN KEY (previous_repayment_id) REFERENCES loan_repayment (repayment_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_loan_repay_reverses
        FOREIGN KEY (reverses_repayment_id) REFERENCES loan_repayment (repayment_id)
        DEFERRABLE INITIALLY DEFERRED
);

-- =============================================
-- 공통 거래 (common_transaction)
-- 자기참조 FK (original_transaction_id) → DEFERRABLE
-- =============================================
CREATE TABLE common_transaction (
    transaction_id              BIGSERIAL       PRIMARY KEY,
    transaction_no              VARCHAR(50),
    account_id                  BIGINT,
    contract_id                 BIGINT,
    transaction_type_cd         VARCHAR(30),
    debit_credit_type           VARCHAR(10),
    transaction_amount          BIGINT,
    balance_before              BIGINT,
    balance_after               BIGINT,
    fee_amount                  BIGINT,
    channel_cd                  VARCHAR(30),
    counterparty_bank_cd        VARCHAR(10),
    counterparty_bank_name      VARCHAR(100),
    counterparty_account_no     VARCHAR(30),
    counterparty_name           VARCHAR(100),
    counterparty_customer_id    BIGINT,
    counterparty_account_id     BIGINT,
    counterparty_name_verified_yn CHAR(1),
    original_transaction_id     BIGINT          NOT NULL,
    transaction_memo            VARCHAR(255),
    transaction_status          VARCHAR(20),
    transacted_at               TIMESTAMPTZ(3),
    currency_cd                 CHAR(3),
    available_balance           BIGINT,
    transaction_summary         VARCHAR(100),
    transfer_type_cd            VARCHAR(30),
    transfer_requested_at       TIMESTAMPTZ(3),
    transfer_completed_at       TIMESTAMPTZ(3),
    transfer_failed_yn          CHAR(1),
    payment_method_code         VARCHAR(30),
    card_payment_yn             CHAR(1),
    payment_failed_yn           CHAR(1),
    merchant_no                 VARCHAR(50),
    merchant_name               VARCHAR(100),
    failure_type_cd             VARCHAR(30),
    failure_reason_cd           VARCHAR(50),
    failure_cause_cd            VARCHAR(50),
    failed_at                   TIMESTAMPTZ(3),
    retry_count                 INT,
    approval_no                 VARCHAR(50),
    external_transaction_no     VARCHAR(100),
    terminal_id                 VARCHAR(50),
    client_ip                   VARCHAR(45),
    transaction_location        VARCHAR(100),
    ledger_posted_at            TIMESTAMPTZ(3),
    cancelled_at                TIMESTAMPTZ(3),
    created_at                  TIMESTAMPTZ(3),
    created_by                  BIGINT,
    updated_at                  TIMESTAMPTZ(3),
    updated_by                  BIGINT,
    CONSTRAINT fk_common_tx_account
        FOREIGN KEY (account_id)              REFERENCES common_account (account_id),
    CONSTRAINT fk_common_tx_contract
        FOREIGN KEY (contract_id)             REFERENCES common_contract (contract_id),
    CONSTRAINT fk_common_tx_original
        FOREIGN KEY (original_transaction_id) REFERENCES common_transaction (transaction_id)
        DEFERRABLE INITIALLY DEFERRED
);

-- =============================================
-- 챗봇·상담 테이블은 consultation-service가 소유.
-- SQLAlchemy create_all() 로 consultation-service 기동 시 자동 생성됨.
-- (V12에서 기존 불일치 테이블 DROP 처리)
-- =============================================
-- 인덱스
-- =============================================
CREATE INDEX idx_party_type_status      ON party (party_type_code, party_status_code);
CREATE INDEX idx_party_role_party       ON party_role (party_id);
CREATE INDEX idx_party_relation_from    ON party_relation (from_party_id);
CREATE INDEX idx_party_relation_to      ON party_relation (to_party_id);

CREATE INDEX idx_customer_party         ON customer (party_id);
CREATE INDEX idx_customer_status        ON customer (customer_status_code);

CREATE INDEX idx_common_product_biz     ON common_product (biz_div_cd, product_status);
CREATE INDEX idx_common_product_cd      ON common_product (product_cd);

CREATE INDEX idx_common_terms_no        ON common_terms_template (terms_no);
CREATE INDEX idx_terms_target_template  ON terms_target_map (terms_template_id);
CREATE INDEX idx_terms_consent_customer ON common_terms_consent (customer_id);
CREATE INDEX idx_terms_consent_template ON common_terms_consent (terms_template_id);

CREATE INDEX idx_common_contract_cust   ON common_contract (customer_id, contract_status);
CREATE INDEX idx_common_contract_prod   ON common_contract (product_id);
CREATE INDEX idx_common_account_cust    ON common_account (customer_id, account_status);

CREATE INDEX idx_loan_app_customer      ON loan_application (customer_id, apply_status);
CREATE INDEX idx_loan_review_app        ON loan_review (application_id);
CREATE INDEX idx_loan_exec_contract     ON loan_execution (contract_id);
CREATE INDEX idx_loan_repay_contract    ON loan_repayment (contract_id);
CREATE INDEX idx_loan_repay_account     ON loan_repayment (loan_account_id);

CREATE INDEX idx_common_tx_account      ON common_transaction (account_id, transacted_at DESC);
CREATE INDEX idx_common_tx_contract     ON common_transaction (contract_id, transacted_at DESC)
    WHERE contract_id IS NOT NULL;

COMMIT;
