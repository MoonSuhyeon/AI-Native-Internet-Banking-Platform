-- =============================================================================
-- 심사자문 — *_yn CHAR(1) 을 BOOLEAN 으로 (4개 컬럼)
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
            ('review_advisory_rule', 'active_yn'),
            ('review_advisory_ack', 'decision_change_yn'),
            ('advisory_document', 'active_yn'),
            ('advisory_case_index', 'overturn_yn')
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

ALTER TABLE review_advisory_rule ALTER COLUMN active_yn DROP DEFAULT;
ALTER TABLE review_advisory_rule ALTER COLUMN active_yn TYPE BOOLEAN
    USING (CASE WHEN active_yn IS NULL THEN NULL ELSE upper(active_yn) IN ('Y','T','1','TRUE') END);
ALTER TABLE review_advisory_ack ALTER COLUMN decision_change_yn DROP DEFAULT;
ALTER TABLE review_advisory_ack ALTER COLUMN decision_change_yn TYPE BOOLEAN
    USING (CASE WHEN decision_change_yn IS NULL THEN NULL ELSE upper(decision_change_yn) IN ('Y','T','1','TRUE') END);
ALTER TABLE advisory_document ALTER COLUMN active_yn DROP DEFAULT;
ALTER TABLE advisory_document ALTER COLUMN active_yn TYPE BOOLEAN
    USING (CASE WHEN active_yn IS NULL THEN NULL ELSE upper(active_yn) IN ('Y','T','1','TRUE') END);
ALTER TABLE advisory_case_index ALTER COLUMN overturn_yn DROP DEFAULT;
ALTER TABLE advisory_case_index ALTER COLUMN overturn_yn TYPE BOOLEAN
    USING (CASE WHEN overturn_yn IS NULL THEN NULL ELSE upper(overturn_yn) IN ('Y','T','1','TRUE') END);

-- 원래 기본값이 있던 컬럼만 복구한다(없던 컬럼은 NULL 허용 그대로).
ALTER TABLE review_advisory_rule ALTER COLUMN active_yn SET DEFAULT FALSE;
ALTER TABLE review_advisory_ack ALTER COLUMN decision_change_yn SET DEFAULT FALSE;
ALTER TABLE advisory_document ALTER COLUMN active_yn SET DEFAULT FALSE;
ALTER TABLE advisory_case_index ALTER COLUMN overturn_yn SET DEFAULT FALSE;
