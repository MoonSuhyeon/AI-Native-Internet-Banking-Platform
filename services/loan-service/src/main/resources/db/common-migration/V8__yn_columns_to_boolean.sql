-- =============================================================================
-- 공통계 — *_yn CHAR(1) 을 BOOLEAN 으로 (14개 컬럼)
--
-- **왜.** 레포에서 플래그 컬럼의 값 체계가 갈려 있었다. 이름은 전부 _yn 인데
-- customer-service 만 'T'/'F' 를 쓰고 나머지는 'Y'/'N' 을 썼다. 다른 도메인을 보던 사람이
-- 반대쪽 리터럴로 비교하면 **에러 없이 0건**이 나온다.
--
-- 값 체계를 문서로 못박는 선택지도 있었지만 그건 갈라짐을 영구화한다. 타입이 뜻을 담게
-- 하면 문서가 필요 없어진다. deposit·payment 는 이미 BOOLEAN 이다.
--
-- **이름은 그대로 둔다.** _yn 접미사는 "예/아니오 플래그"라는 뜻이라 BOOLEAN 과 어긋나지
-- 않는다. 이름 변경은 별개 관심사이고 타입 변경과 섞으면 리뷰가 불가능해진다.
--
-- 변환은 참으로 읽을 값 집합을 명시하고 나머지를 거짓으로 둔다. NULL 은 NULL 로 남긴다
-- (기본값 없는 컬럼이 있어 삼항 논리를 보존한다).
-- =============================================================================

-- 순수 도메인 CHECK 를 먼저 걷는다. CHECK (col IN ('Y','N')) 은 "값이 둘뿐"이라는 뜻인데
-- BOOLEAN 이 되면 타입이 그 보장을 한다. 인라인 CHECK 는 Postgres 가 이름을 자동 생성하므로
-- 카탈로그에서 찾아 지운다.
DO $$
DECLARE
    target RECORD;
    con    RECORD;
BEGIN
    FOR target IN
        SELECT * FROM (VALUES
            ('common_account', 'suspic_account_yn'),
            ('common_product', 'policy_product_yn'),
            ('common_product', 'sale_yn'),
            ('common_product', 'financial_consumer_act_yn'),
            ('common_terms_template', 'required_yn'),
            ('common_terms_template', 'active_yn'),
            ('common_contract', 'auto_transfer_yn'),
            ('common_contract', 'proxy_yn'),
            ('common_terms_consent', 'agreed_yn'),
            ('common_terms_consent', 'withdrawn_yn'),
            ('common_transaction', 'counterparty_name_verified_yn'),
            ('common_transaction', 'transfer_failed_yn'),
            ('common_transaction', 'card_payment_yn'),
            ('common_transaction', 'payment_failed_yn')
        ) AS t(tbl, col)
    LOOP
        IF to_regclass(target.tbl) IS NULL THEN CONTINUE; END IF;
        FOR con IN
            SELECT c.conname, c.conrelid::regclass AS rel
            FROM pg_constraint c
            JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
            WHERE c.contype = 'c' AND c.conrelid = target.tbl::regclass AND a.attname = target.col
        LOOP
            EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', con.rel, con.conname);
        END LOOP;
    END LOOP;
END $$;

ALTER TABLE common_account ALTER COLUMN suspic_account_yn DROP DEFAULT;
ALTER TABLE common_account ALTER COLUMN suspic_account_yn TYPE BOOLEAN
    USING (CASE WHEN suspic_account_yn IS NULL THEN NULL ELSE upper(suspic_account_yn) IN ('Y','T','1','TRUE') END);
ALTER TABLE common_product ALTER COLUMN policy_product_yn DROP DEFAULT;
ALTER TABLE common_product ALTER COLUMN policy_product_yn TYPE BOOLEAN
    USING (CASE WHEN policy_product_yn IS NULL THEN NULL ELSE upper(policy_product_yn) IN ('Y','T','1','TRUE') END);
ALTER TABLE common_product ALTER COLUMN sale_yn DROP DEFAULT;
ALTER TABLE common_product ALTER COLUMN sale_yn TYPE BOOLEAN
    USING (CASE WHEN sale_yn IS NULL THEN NULL ELSE upper(sale_yn) IN ('Y','T','1','TRUE') END);
ALTER TABLE common_product ALTER COLUMN financial_consumer_act_yn DROP DEFAULT;
ALTER TABLE common_product ALTER COLUMN financial_consumer_act_yn TYPE BOOLEAN
    USING (CASE WHEN financial_consumer_act_yn IS NULL THEN NULL ELSE upper(financial_consumer_act_yn) IN ('Y','T','1','TRUE') END);
ALTER TABLE common_terms_template ALTER COLUMN required_yn DROP DEFAULT;
ALTER TABLE common_terms_template ALTER COLUMN required_yn TYPE BOOLEAN
    USING (CASE WHEN required_yn IS NULL THEN NULL ELSE upper(required_yn) IN ('Y','T','1','TRUE') END);
ALTER TABLE common_terms_template ALTER COLUMN active_yn DROP DEFAULT;
ALTER TABLE common_terms_template ALTER COLUMN active_yn TYPE BOOLEAN
    USING (CASE WHEN active_yn IS NULL THEN NULL ELSE upper(active_yn) IN ('Y','T','1','TRUE') END);
ALTER TABLE common_contract ALTER COLUMN auto_transfer_yn DROP DEFAULT;
ALTER TABLE common_contract ALTER COLUMN auto_transfer_yn TYPE BOOLEAN
    USING (CASE WHEN auto_transfer_yn IS NULL THEN NULL ELSE upper(auto_transfer_yn) IN ('Y','T','1','TRUE') END);
ALTER TABLE common_contract ALTER COLUMN proxy_yn DROP DEFAULT;
ALTER TABLE common_contract ALTER COLUMN proxy_yn TYPE BOOLEAN
    USING (CASE WHEN proxy_yn IS NULL THEN NULL ELSE upper(proxy_yn) IN ('Y','T','1','TRUE') END);
ALTER TABLE common_terms_consent ALTER COLUMN agreed_yn DROP DEFAULT;
ALTER TABLE common_terms_consent ALTER COLUMN agreed_yn TYPE BOOLEAN
    USING (CASE WHEN agreed_yn IS NULL THEN NULL ELSE upper(agreed_yn) IN ('Y','T','1','TRUE') END);
ALTER TABLE common_terms_consent ALTER COLUMN withdrawn_yn DROP DEFAULT;
ALTER TABLE common_terms_consent ALTER COLUMN withdrawn_yn TYPE BOOLEAN
    USING (CASE WHEN withdrawn_yn IS NULL THEN NULL ELSE upper(withdrawn_yn) IN ('Y','T','1','TRUE') END);
ALTER TABLE common_transaction ALTER COLUMN counterparty_name_verified_yn DROP DEFAULT;
ALTER TABLE common_transaction ALTER COLUMN counterparty_name_verified_yn TYPE BOOLEAN
    USING (CASE WHEN counterparty_name_verified_yn IS NULL THEN NULL ELSE upper(counterparty_name_verified_yn) IN ('Y','T','1','TRUE') END);
ALTER TABLE common_transaction ALTER COLUMN transfer_failed_yn DROP DEFAULT;
ALTER TABLE common_transaction ALTER COLUMN transfer_failed_yn TYPE BOOLEAN
    USING (CASE WHEN transfer_failed_yn IS NULL THEN NULL ELSE upper(transfer_failed_yn) IN ('Y','T','1','TRUE') END);
ALTER TABLE common_transaction ALTER COLUMN card_payment_yn DROP DEFAULT;
ALTER TABLE common_transaction ALTER COLUMN card_payment_yn TYPE BOOLEAN
    USING (CASE WHEN card_payment_yn IS NULL THEN NULL ELSE upper(card_payment_yn) IN ('Y','T','1','TRUE') END);
ALTER TABLE common_transaction ALTER COLUMN payment_failed_yn DROP DEFAULT;
ALTER TABLE common_transaction ALTER COLUMN payment_failed_yn TYPE BOOLEAN
    USING (CASE WHEN payment_failed_yn IS NULL THEN NULL ELSE upper(payment_failed_yn) IN ('Y','T','1','TRUE') END);

-- 원래 기본값이 있던 컬럼만 복구한다(없던 컬럼은 NULL 허용 그대로).
ALTER TABLE common_terms_template ALTER COLUMN required_yn SET DEFAULT TRUE;
ALTER TABLE common_terms_template ALTER COLUMN active_yn SET DEFAULT TRUE;
ALTER TABLE common_terms_consent ALTER COLUMN withdrawn_yn SET DEFAULT FALSE;
