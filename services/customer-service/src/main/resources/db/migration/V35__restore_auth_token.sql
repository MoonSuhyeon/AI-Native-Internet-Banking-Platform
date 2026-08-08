-- auth_token 복구.
--
-- 배경: V7 이 이 테이블을 만들었고 V9 가 지웠다. 지울 당시 판단은 옳았다 —
-- 설계문서에 없고 참조 코드가 0건인 선반영 스키마였다.
--
-- 그 뒤 자금이동 step-up 인증(docs/plan/transfer-step-up-auth.md)이 들어오면서
-- AuthToken 엔티티와 TransactionApprovalService 가 이 테이블을 쓰기 시작했는데,
-- 테이블을 되살리는 마이그레이션이 함께 오지 않았다.
--
-- 결과: 승인 토큰 발급이 항상 500 이었다. TRANSFER_APPROVAL_REQUIRED=true 인 지금
-- 소액을 넘는 이체는 아무도 할 수 없다. 발급이 실패하니 토큰을 얻을 방법이 없고,
-- 토큰이 없으면 게이트가 fail-closed 로 막기 때문이다.
--
-- 컴파일도 되고 기동도 되며 화면도 멀쩡하다. 실제로 이체를 태워 봐야 드러난다.
--
-- 정의는 V7 원본을 그대로 따른다. 엔티티가 그 컬럼명을 기대하고 있고,
-- 여기서 이름을 바꾸면 지금 도는 코드가 다시 깨진다.

CREATE TABLE IF NOT EXISTS auth_token (
    auth_token_id               BIGINT          GENERATED ALWAYS AS IDENTITY,
    customer_id                 BIGINT          NOT NULL,

    -- 토큰 원문은 저장하지 않는다. 유출돼도 재사용할 수 없어야 한다.
    auth_token_hash             VARCHAR(255)    NOT NULL,

    auth_method_type_code       VARCHAR(20)     NOT NULL,
    auth_token_purpose_code     VARCHAR(30)     NOT NULL,
    auth_token_status_code      VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',

    auth_token_issued_at        TIMESTAMPTZ(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    auth_token_expiry_at        TIMESTAMPTZ(3)  NOT NULL,

    -- 1회용이라 쓰인 시각이 남아야 재사용 시도를 구별할 수 있다.
    auth_token_used_at          TIMESTAMPTZ(3),

    created_at                  TIMESTAMPTZ(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by                  BIGINT,
    updated_at                  TIMESTAMPTZ(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by                  BIGINT,
    deleted_at                  TIMESTAMPTZ(3),
    deleted_by                  BIGINT,
    version                     INT             NOT NULL DEFAULT 0,

    CONSTRAINT pk_auth_token PRIMARY KEY (auth_token_id),

    -- 같은 해시가 두 번 저장되면 1회용이 깨진다.
    CONSTRAINT uq_auth_token_hash UNIQUE (auth_token_hash),

    CONSTRAINT fk_auth_token_customer FOREIGN KEY (customer_id)
        REFERENCES customer(customer_id),

    CONSTRAINT chk_auth_token_status CHECK (
        auth_token_status_code IN ('ACTIVE', 'USED', 'EXPIRED', 'REVOKED'))
);

-- 검증은 언제나 "이 고객의 살아 있는 토큰" 을 찾는다.
CREATE INDEX IF NOT EXISTS ix_auth_token_customer_status
    ON auth_token (customer_id, auth_token_status_code);
