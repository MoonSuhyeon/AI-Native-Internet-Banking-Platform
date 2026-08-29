-- 약관 동의 이력 — 고객 도메인이 정본(SoR)이다.
--
-- 지금까지 이 자리가 비어 있었다. 화면은 동의 체크박스를 받았지만 어디에도 남지
-- 않았고(React 상태로만 존재), 그래서 "이 고객이 무엇에 언제 동의했는가" 를 물으면
-- 답할 방법이 없었다. 규제 관점에서 그것은 동의를 받지 않은 것과 같다.
--
-- ## 왜 common_db 가 아니라 여기인가
--
-- common_db 에 common_terms_consent 가 있었다. 그런데 그 표는 loan-service 의
-- 마이그레이션이 만들고, 고객번호는 FK 를 걸 수 없어 값 참조였으며, 읽고 쓰는 코드가
-- 하나도 없었다(템플릿 0건·동의 0건).
--
-- 동의는 **고객에 대한 사실**이다. 고객 신원과 상태를 아는 곳이 그것을 지켜야 한다.
-- 여기로 옮기면 customer FK 를 실제로 걸 수 있다 — 없는 고객의 동의가 생기지 않고,
-- 고객 이력 조회가 서비스 경계를 넘지 않는다. common_db 쪽 표는 제거한다.
-- 같은 것이 두 곳에 있으면 어느 쪽이 정본인지 나중에 아무도 모른다.
--
-- ## 템플릿을 함께 옮기는 이유
--
-- "동의했다" 만으로는 부족하고 **무엇에 동의했는가** 를 말할 수 있어야 증명이 된다.
-- 그래서 동의와 템플릿은 감사 관점에서 한 덩어리다. common_terms_template 의 유일한
-- 소비자도 이 동의 표의 FK 뿐이었다(자바 엔티티도, 읽는 코드도 없었다).

-- ── 약관 템플릿 ─────────────────────────────────────────────────────────────
CREATE TABLE terms_template (
    terms_template_id   BIGINT          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 약관 식별 번호. 개정되면 새 행이고 새 번호다 — 같은 번호의 내용이 바뀌면
    -- 과거 동의가 무엇에 대한 것이었는지 사라진다.
    terms_no            VARCHAR(50)     NOT NULL UNIQUE,
    terms_name          VARCHAR(200)    NOT NULL,
    terms_category_cd   VARCHAR(20)     NOT NULL,
    terms_version       VARCHAR(20)     NOT NULL DEFAULT '1.0',
    description         TEXT,
    -- 필수 약관은 거절하면 그 업무를 진행할 수 없다. 선택은 거절해도 진행된다.
    required_yn         BOOLEAN         NOT NULL DEFAULT TRUE,
    -- 어느 업무의 약관인가 (CERT · ACCOUNT · LOAN · MARKETING …)
    biz_div_cd          VARCHAR(50)     NOT NULL,
    active_yn           BOOLEAN         NOT NULL DEFAULT TRUE,
    effective_from      CHAR(8),
    effective_to        CHAR(8),
    created_at          TIMESTAMPTZ(3)  NOT NULL DEFAULT now(),
    created_by          BIGINT,
    updated_at          TIMESTAMPTZ(3)  NOT NULL DEFAULT now(),
    updated_by          BIGINT
);

CREATE INDEX idx_terms_template_biz ON terms_template (biz_div_cd, active_yn);

COMMENT ON TABLE terms_template IS '약관 템플릿. 동의가 가리키는 대상이며 개정 시 새 행으로 남긴다.';

-- ── 동의 이력 ───────────────────────────────────────────────────────────────
CREATE TABLE terms_consent (
    -- PK 를 단일 컬럼으로 둔다. 원본(common_terms_consent)은 (consent_id, customer_id)
    -- 복합키였는데, consent_id 가 이미 유일하므로 더해 주는 것이 없었다. 대신 이 표를
    -- 가리키는 모든 곳이 고객번호를 함께 들고 다녀야 했다.
    consent_id          BIGINT          GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- common_db 시절에는 걸 수 없던 FK 다. 고객 도메인으로 옮겨 온 실질적 이득이
    -- 여기다 — 없는 고객의 동의가 생기지 않는다.
    customer_id         BIGINT          NOT NULL
        REFERENCES customer (customer_id),
    terms_template_id   BIGINT          NOT NULL
        REFERENCES terms_template (terms_template_id),

    biz_div_cd          VARCHAR(50)     NOT NULL,
    -- 어느 건에 대한 동의인가 (인증서 발급 id · 대출 신청 id 등). 도메인이 달라
    -- FK 는 걸지 않고 값으로만 둔다.
    consent_target_id   BIGINT,

    -- 동의했는가. 거절도 기록한다 — "안 받았다" 와 "거절했다" 는 다른 사실이고,
    -- 뒤에 분쟁이 되는 것은 대개 그 차이다.
    agreed_yn           BOOLEAN         NOT NULL,
    agreed_at           TIMESTAMPTZ(3)  NOT NULL DEFAULT now(),

    -- 어떻게 받았는가 (WEB · MOBILE · BRANCH · ARS). 분쟁 시 경로가 쟁점이 된다.
    consent_method_cd   VARCHAR(20)     NOT NULL,
    -- 무엇으로 받았는가. 브라우저·앱 버전 등.
    consent_tool        VARCHAR(500),
    -- 서명 문서를 남기는 경우의 위치와 해시. 내용이 바뀌지 않았음을 뒤에 증명한다.
    signed_doc_url      VARCHAR(500),
    signed_doc_hash     VARCHAR(64),
    -- INET 이 아니라 문자열이다. 서브넷 연산을 쓸 일이 없고, INET 은 테스트에서 도는
    -- H2 가 모른다. 쓰지 않는 기능 때문에 이식성을 잃을 이유가 없다.
    -- 45자는 IPv6 최대 표기 길이다.
    client_ip           VARCHAR(45),

    -- 철회. 동의는 지우는 것이 아니라 철회하는 것이다 — 동의했던 사실 자체는 남는다.
    withdrawn_yn        BOOLEAN         NOT NULL DEFAULT FALSE,
    withdrawn_at        TIMESTAMPTZ(3),
    withdrawn_reason    VARCHAR(500),

    -- 보존 기한. 지나면 파기 대상이 된다(파기 배치는 아직 없다 — OPEN_ITEMS).
    retention_until     CHAR(8),

    created_at          TIMESTAMPTZ(3)  NOT NULL DEFAULT now(),
    created_by          BIGINT,
    updated_at          TIMESTAMPTZ(3)  NOT NULL DEFAULT now(),
    updated_by          BIGINT,

    -- 철회는 시각이 있어야 성립한다. 플래그만 서고 시각이 없으면 언제부터 효력이
    -- 없어졌는지 말할 수 없다.
    CONSTRAINT ck_terms_consent_withdrawn
        CHECK (withdrawn_yn = FALSE OR withdrawn_at IS NOT NULL)
);

CREATE INDEX idx_terms_consent_customer  ON terms_consent (customer_id, agreed_at DESC);
CREATE INDEX idx_terms_consent_template  ON terms_consent (terms_template_id);
CREATE INDEX idx_terms_consent_target    ON terms_consent (biz_div_cd, consent_target_id);

COMMENT ON TABLE terms_consent IS '고객 약관 동의 이력. 고객 도메인이 정본이며 철회는 기록으로 남긴다.';

-- ── 고쳐 쓰지 못하게 한다 ───────────────────────────────────────────────────
--
-- 동의 이력은 뒤에 분쟁의 근거가 되는 기록이다. 지울 수 있으면 근거가 아니고,
-- 아무 컬럼이나 고칠 수 있으면 "나중에 맞춰 쓴 것" 과 구분되지 않는다.
--
-- 그렇다고 완전히 못 고치게 할 수는 없다. 철회는 고객의 권리라 반드시 되어야 한다.
-- 그래서 **철회 관련 컬럼만** 열어 둔다. 동의 사실·시점·대상은 잠근다.
CREATE OR REPLACE FUNCTION fn_terms_consent_guard()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION
            'terms_consent 는 삭제할 수 없다. 동의를 거두려면 철회로 기록할 것.';
    END IF;

    IF NEW.consent_id        IS DISTINCT FROM OLD.consent_id
    OR NEW.customer_id       IS DISTINCT FROM OLD.customer_id
    OR NEW.terms_template_id IS DISTINCT FROM OLD.terms_template_id
    OR NEW.biz_div_cd        IS DISTINCT FROM OLD.biz_div_cd
    OR NEW.consent_target_id IS DISTINCT FROM OLD.consent_target_id
    OR NEW.agreed_yn         IS DISTINCT FROM OLD.agreed_yn
    OR NEW.agreed_at         IS DISTINCT FROM OLD.agreed_at
    OR NEW.consent_method_cd IS DISTINCT FROM OLD.consent_method_cd
    OR NEW.consent_tool      IS DISTINCT FROM OLD.consent_tool
    OR NEW.signed_doc_url    IS DISTINCT FROM OLD.signed_doc_url
    OR NEW.signed_doc_hash   IS DISTINCT FROM OLD.signed_doc_hash
    OR NEW.client_ip         IS DISTINCT FROM OLD.client_ip
    OR NEW.created_at        IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION
            'terms_consent 의 동의 사실은 고칠 수 없다. 철회 컬럼만 변경할 수 있다.';
    END IF;

    -- 철회는 되돌릴 수 없다. 되돌리려면 새로 동의받아 새 행으로 남긴다 —
    -- 철회를 지웠다 켰다 할 수 있으면 이력이 사실을 말하지 못한다.
    IF OLD.withdrawn_yn = TRUE AND NEW.withdrawn_yn = FALSE THEN
        RAISE EXCEPTION
            '철회는 되돌릴 수 없다. 다시 동의받아 새 이력으로 남길 것.';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_terms_consent_no_delete
    BEFORE DELETE ON terms_consent
    FOR EACH ROW EXECUTE FUNCTION fn_terms_consent_guard();

CREATE TRIGGER trg_terms_consent_guard_update
    BEFORE UPDATE ON terms_consent
    FOR EACH ROW EXECUTE FUNCTION fn_terms_consent_guard();
