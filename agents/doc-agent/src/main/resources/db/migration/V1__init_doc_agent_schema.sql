-- doc-agent Phase D-0: 초기 스키마

-- 대출 상품별 필수 서류 마스터
CREATE TABLE loan_product_documents (
    product_id          VARCHAR(10)  NOT NULL,
    product_name        VARCHAR(100) NOT NULL,
    req_doc_code        VARCHAR(10)  NOT NULL,
    req_doc_name        VARCHAR(100) NOT NULL,
    is_essential        BOOLEAN      NOT NULL DEFAULT TRUE,
    valid_days          INT,                              -- NULL = 만료 없음
    accepted_formats    VARCHAR(100) NOT NULL DEFAULT 'pdf,jpg,png',
    min_dpi             INT          NOT NULL DEFAULT 200,
    issuer_type         VARCHAR(20)  NOT NULL,            -- GOV24|COMPANY|BANK|PRIVATE
    auto_verify_enabled BOOLEAN      NOT NULL DEFAULT TRUE, -- FALSE = 무조건 심사원 라우팅
    retention_days      INT,
    PRIMARY KEY (product_id, req_doc_code)
);

-- 초기 마스터 데이터
INSERT INTO loan_product_documents VALUES
  ('P001','직장인 신용대출','DOC_01','신분증 (주민증/면허증)',TRUE,NULL,'pdf,jpg,png',200,'GOV24',TRUE,1825),
  ('P001','직장인 신용대출','DOC_02','주민등록등본',         TRUE,90,  'pdf,jpg,png',200,'GOV24',TRUE,1825),
  ('P001','직장인 신용대출','DOC_03','재직증명서',           TRUE,30,  'pdf,jpg,png',200,'COMPANY',TRUE,1825),
  ('P001','직장인 신용대출','DOC_04','근로소득원천징수영수증',TRUE,365, 'pdf',        200,'COMPANY',TRUE,1825),
  ('P002','주택담보대출',   'DOC_01','신분증 (주민증/면허증)',TRUE,NULL,'pdf,jpg,png',200,'GOV24',TRUE,1825),
  ('P002','주택담보대출',   'DOC_05','부동산 등기부등본',    TRUE,90,  'pdf',        200,'GOV24',TRUE,1825),
  ('P002','주택담보대출',   'DOC_06','매매계약서',           TRUE,NULL,'pdf',        200,'PRIVATE',FALSE,1825);

-- 서류 제출 운영 로그
CREATE TABLE loan_document_submission (
    submission_id      UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    application_id     VARCHAR(50)  NOT NULL,
    doc_code           VARCHAR(10)  NOT NULL,
    raw_object_key     VARCHAR(500),   -- MinIO/R2 원본 경로 (Vault Transit 암호화)
    masked_object_key  VARCHAR(500),   -- 마스킹본 경로
    forgery_score      NUMERIC(3,2),   -- 자동 산출 점수 (사람이 판정하는 게 아님)
    verify_status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
                                       -- PENDING|AUTO_PASS|NEEDS_RESUBMIT|HOLD|LOCKED|CLEARED
    reviewer_id        VARCHAR(50),
    human_review_status VARCHAR(20) DEFAULT 'NOT_REQUIRED',
                                       -- NOT_REQUIRED|PENDING|CLEARED|CONFIRMED_FORGERY
    retention_until    DATE,
    legal_hold         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_doc_submission_application ON loan_document_submission(application_id);
CREATE INDEX idx_doc_submission_status ON loan_document_submission(verify_status);
CREATE INDEX idx_doc_submission_retention ON loan_document_submission(retention_until)
    WHERE legal_hold = FALSE;

-- 위변조 시그널 로그 (향후 모델 학습 데이터)
CREATE TABLE loan_forgery_signal (
    signal_id     BIGSERIAL    PRIMARY KEY,
    submission_id UUID         NOT NULL REFERENCES loan_document_submission(submission_id),
    category      VARCHAR(20)  NOT NULL,  -- META|VISUAL|SEMANTIC|EXTERNAL
    signal_type   VARCHAR(50)  NOT NULL,  -- META_EDIT_TOOL|ELA_HIGH|COPY_MOVE|SSN_CHECKSUM_FAIL...
    score         NUMERIC(3,2) NOT NULL,
    evidence      JSONB,
    detected_at   TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_forgery_signal_submission ON loan_forgery_signal(submission_id);
CREATE INDEX idx_forgery_signal_category   ON loan_forgery_signal(category, signal_type);
