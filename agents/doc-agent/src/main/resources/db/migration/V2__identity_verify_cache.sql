-- doc-agent Phase D-3: 진위확인 결과 캐시 (TTL 1일)

CREATE TABLE identity_verify_cache (
    cache_key   VARCHAR(200) PRIMARY KEY,   -- SHA-256(api_type + params), PII 미포함
    result      VARCHAR(20)  NOT NULL,      -- VALID|INVALID|ERROR
    verified_at TIMESTAMP    NOT NULL DEFAULT now(),
    expires_at  TIMESTAMP    NOT NULL
);

CREATE INDEX idx_identity_verify_expires ON identity_verify_cache(expires_at);

-- 마스터 테이블 조회 성능 인덱스
CREATE INDEX idx_lpd_product_essential ON loan_product_documents(product_id, is_essential);
