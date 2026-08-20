-- 거래 증빙 발급 — 거래영수증·이체확인증.
--
-- 화면에는 "거래영수증"(거래내역 조회)과 "이체확인증 건별/일괄 출력"(이체 결과조회)
-- 버튼이 있었지만 셋 다 아무 데도 연결돼 있지 않았다. 레포 전체에서 receipt·증빙
-- 관련 코드가 0건이었다. 거래 증빙은 은행의 기본 의무라 화면만 있고 기능이 없는 채로
-- 둘 수 없다.
--
-- **왜 별도 테이블인가.** 증빙을 "거래를 조회해 보여주기" 로 만들면 발급 사실이 남지
-- 않는다. 증빙은 발급하는 순간 문서가 되고, 그 문서에는 나중에 대조할 수 있는 번호가
-- 있어야 한다. 그리고 누구에게 언제 발급됐는지는 그 자체가 개인정보 접근 기록이다.
--
-- **재발급을 막지 않는다.** 실무에서 증빙은 다시 뽑을 수 있고, 대신 뽑을 때마다 새
-- 번호가 붙고 이력이 쌓인다. 같은 번호를 재사용하면 "언제 발급된 문서인가" 를
-- 구별할 수 없다.

CREATE TABLE IF NOT EXISTS deposit_transaction_certificate (
    certificate_id       BIGSERIAL PRIMARY KEY,

    -- 문서의 신원. 발급된 종이·PDF 에 찍히는 번호이며 이것으로 대조한다.
    certificate_no       VARCHAR(40)  NOT NULL UNIQUE,

    transaction_id       BIGINT       NOT NULL
        REFERENCES deposit_transactions (transaction_id),

    -- RECEIPT(거래영수증) · TRANSFER_CONFIRMATION(이체확인증)
    certificate_type     VARCHAR(30)  NOT NULL,

    -- 누구에게 발급했나. 게이트웨이가 검증한 고객번호만 들어간다.
    issued_to_customer_id VARCHAR(30) NOT NULL,
    issued_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),

    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  deposit_transaction_certificate IS '거래 증빙 발급 이력. 발급할 때마다 한 행이 쌓인다.';
COMMENT ON COLUMN deposit_transaction_certificate.certificate_no IS
    '증빙 번호. 발급 문서의 신원이며 재발급 시 새로 부여된다.';
COMMENT ON COLUMN deposit_transaction_certificate.issued_to_customer_id IS
    '발급 대상 고객. 요청 본문이 아니라 게이트웨이가 주입한 X-Customer-Id 만 쓴다.';

-- 종류는 둘뿐이다. 오타로 새 값이 들어가면 조회·집계가 조용히 어긋난다.
ALTER TABLE deposit_transaction_certificate
    DROP CONSTRAINT IF EXISTS ck_txn_cert_type;
ALTER TABLE deposit_transaction_certificate
    ADD CONSTRAINT ck_txn_cert_type
    CHECK (certificate_type IN ('RECEIPT', 'TRANSFER_CONFIRMATION'));

-- 한 거래의 발급 이력을 최근순으로 본다.
CREATE INDEX IF NOT EXISTS ix_txn_cert_transaction
    ON deposit_transaction_certificate (transaction_id, issued_at DESC);

-- 내가 뽑은 증빙 목록.
CREATE INDEX IF NOT EXISTS ix_txn_cert_customer
    ON deposit_transaction_certificate (issued_to_customer_id, issued_at DESC);
