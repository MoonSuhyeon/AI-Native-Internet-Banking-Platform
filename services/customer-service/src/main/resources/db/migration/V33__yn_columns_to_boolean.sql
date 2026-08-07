-- =============================================================================
-- 고객·인증보안계의 *_yn CHAR(1) 을 BOOLEAN 으로 (24개 컬럼 / 14개 테이블)
--
-- **왜 여기가 먼저인가.** 레포에서 CHAR(1) 플래그의 값 체계가 갈려 있었는데, 무작위가
-- 아니라 이 서비스 하나가 갈라진 것이었다.
--   customer     : 24개 전부 'T'/'F'
--   loan         : 10개 전부 'Y'/'N'
--   common       : 14개 'Y'/'N'
--   advisory     :  4개 'Y'/'N'
--   consultation :  Y'/'N'
--
-- 이름은 다 _yn(=yes/no)인데 값만 T/F 다. 그래서 다른 도메인을 보던 사람이
-- fatca_reportable_yn 에 = 'Y' 를 쓰면 **에러 없이 0건**이 나온다. 규제 보고(FATCA/CRS)
-- 대상 조회에서 조용한 0건은 최악의 실패 방식이다.
--
-- 값 체계를 문서로 못박는 선택지도 있었지만, 그건 갈라짐을 영구화한다. 타입이 뜻을
-- 담게 하면 문서가 필요 없어진다. deposit·payment 는 이미 BOOLEAN 이다.
--
-- **변환 규칙.** 이 스키마는 'T'/'F' 지만, 다른 도메인에서 흘러들어온 값이 섞였을 가능성을
-- 배제하지 않는다. 참으로 읽는 집합을 명시하고 나머지는 거짓으로 둔다.
-- NULL 은 NULL 로 남는다(삼항 논리 유지) — is_sanctioned_yn 처럼 기본값 없는 컬럼이 있다.
--
-- **이름은 그대로 둔다.** _yn 접미사는 "예/아니오 플래그"라는 뜻이라 BOOLEAN 과 어긋나지
-- 않는다. 이름 변경은 별개 관심사이고, 타입 변경과 섞으면 리뷰가 불가능해진다.
--
-- 자바 필드(String → Boolean)와 프론트 비교식('Y'/'T' → true)을 같은 커밋에서 함께 바꾼다.
-- 프론트는 타입 선언이 거의 없어 컴파일러가 잡아주지 않으므로 비교식을 전수 확인했다.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) 순수 도메인 CHECK 를 먼저 걷는다.
--
-- CHECK (col IN ('T','F')) 는 "값이 두 개뿐"이라는 뜻인데, BOOLEAN 이 되면 타입 자체가
-- 그 보장을 한다. 남겨두면 boolean IN (true,false) 라는 항상 참인 식이 되어 의미만 잃는다.
--
-- 컬럼에 붙은 인라인 CHECK 는 Postgres 가 이름을 자동으로 붙이므로(<table>_<column>_check)
-- 이름을 하드코딩하지 않고 카탈로그에서 찾아 지운다.
--
-- chk_party_person_pep 은 다르다. "PEP 이면 유형코드가 있어야 한다"는 업무 규칙이라
-- 여기서 함께 지우고 아래에서 불린 형태로 다시 만든다.
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    target  RECORD;
    con     RECORD;
BEGIN
    FOR target IN
        SELECT * FROM (VALUES
            ('party_person',            'is_pep_yn'),
            ('compliance_info',         'is_ofac_sanctioned_yn'),
            ('compliance_info',         'is_un_sanctioned_yn'),
            ('compliance_info',         'is_eu_sanctioned_yn'),
            ('compliance_info',         'is_kr_sanctioned_yn'),
            ('compliance_info',         'edd_required_yn'),
            ('compliance_info',         'fatca_reportable_yn'),
            ('compliance_info',         'crs_reportable_yn'),
            ('customer',                'main_customer_yn'),
            ('customer',                'sms_receive_yn'),
            ('customer',                'email_receive_yn'),
            ('customer',                'postal_receive_yn'),
            ('customer_status_history', 'system_auto_triggered_yn'),
            ('customer_grade_history',  'system_auto_triggered_yn'),
            ('identity_verification',   'consumed_yn'),
            ('fds_rule',                'fds_rule_active_yn'),
            ('registered_device',       'trusted_device_yn'),
            ('registered_device',       'designated_pc_yn'),
            ('auth_method',             'primary_auth_method_yn'),
            ('mobile_auth',             'mobile_auth_verified_yn'),
            ('login_attempt',           'login_attempt_success_yn'),
            ('login_session',           'session_mfa_completed_yn'),
            ('fds_incident',            'fds_incident_fss_reported_yn')
        ) AS t(tbl, col)
    LOOP
        FOR con IN
            SELECT c.conname, c.conrelid::regclass AS rel
            FROM pg_constraint c
            JOIN pg_attribute a
              ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
            WHERE c.contype = 'c'
              AND c.conrelid = target.tbl::regclass
              AND a.attname  = target.col
        LOOP
            EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', con.rel, con.conname);
        END LOOP;
    END LOOP;
END $$;

-- -----------------------------------------------------------------------------
-- 2) 생성 컬럼을 잠시 걷는다.
--
-- is_sanctioned_yn 은 OFAC·UN·EU·KR 네 플래그의 OR 합산인 GENERATED STORED 컬럼이다.
-- 생성 컬럼은 ALTER ... TYPE ... USING 이 불가능하므로(cannot specify USING when altering
-- type of generated column) 지웠다가 아래에서 불린으로 다시 만든다.
--
-- 이 컬럼에 기본값이 없던 이유가 생성 컬럼이어서였다 — 컬럼 목록만 훑으면 놓치기 쉽다.
-- -----------------------------------------------------------------------------
ALTER TABLE compliance_info DROP COLUMN is_sanctioned_yn;

-- -----------------------------------------------------------------------------
-- 3) 타입 변경
-- -----------------------------------------------------------------------------
-- party_person
ALTER TABLE party_person
    ALTER COLUMN is_pep_yn DROP DEFAULT,
    ALTER COLUMN is_pep_yn TYPE BOOLEAN
        USING (CASE WHEN is_pep_yn IS NULL THEN NULL
                    ELSE upper(is_pep_yn) IN ('T', 'Y', '1', 'TRUE') END);
ALTER TABLE party_person ALTER COLUMN is_pep_yn SET DEFAULT FALSE;

-- compliance_info
ALTER TABLE compliance_info
    ALTER COLUMN is_ofac_sanctioned_yn DROP DEFAULT,
    ALTER COLUMN is_un_sanctioned_yn   DROP DEFAULT,
    ALTER COLUMN is_eu_sanctioned_yn   DROP DEFAULT,
    ALTER COLUMN is_kr_sanctioned_yn   DROP DEFAULT,
    ALTER COLUMN edd_required_yn       DROP DEFAULT,
    ALTER COLUMN fatca_reportable_yn   DROP DEFAULT,
    ALTER COLUMN crs_reportable_yn     DROP DEFAULT;
ALTER TABLE compliance_info
    ALTER COLUMN is_ofac_sanctioned_yn TYPE BOOLEAN
        USING (CASE WHEN is_ofac_sanctioned_yn IS NULL THEN NULL ELSE upper(is_ofac_sanctioned_yn) IN ('T','Y','1','TRUE') END),
    ALTER COLUMN is_un_sanctioned_yn TYPE BOOLEAN
        USING (CASE WHEN is_un_sanctioned_yn IS NULL THEN NULL ELSE upper(is_un_sanctioned_yn) IN ('T','Y','1','TRUE') END),
    ALTER COLUMN is_eu_sanctioned_yn TYPE BOOLEAN
        USING (CASE WHEN is_eu_sanctioned_yn IS NULL THEN NULL ELSE upper(is_eu_sanctioned_yn) IN ('T','Y','1','TRUE') END),
    ALTER COLUMN is_kr_sanctioned_yn TYPE BOOLEAN
        USING (CASE WHEN is_kr_sanctioned_yn IS NULL THEN NULL ELSE upper(is_kr_sanctioned_yn) IN ('T','Y','1','TRUE') END),
    ALTER COLUMN edd_required_yn TYPE BOOLEAN
        USING (CASE WHEN edd_required_yn IS NULL THEN NULL ELSE upper(edd_required_yn) IN ('T','Y','1','TRUE') END),
    ALTER COLUMN fatca_reportable_yn TYPE BOOLEAN
        USING (CASE WHEN fatca_reportable_yn IS NULL THEN NULL ELSE upper(fatca_reportable_yn) IN ('T','Y','1','TRUE') END),
    ALTER COLUMN crs_reportable_yn TYPE BOOLEAN
        USING (CASE WHEN crs_reportable_yn IS NULL THEN NULL ELSE upper(crs_reportable_yn) IN ('T','Y','1','TRUE') END);
ALTER TABLE compliance_info
    ALTER COLUMN is_ofac_sanctioned_yn SET DEFAULT FALSE,
    ALTER COLUMN is_un_sanctioned_yn   SET DEFAULT FALSE,
    ALTER COLUMN is_eu_sanctioned_yn   SET DEFAULT FALSE,
    ALTER COLUMN is_kr_sanctioned_yn   SET DEFAULT FALSE,
    ALTER COLUMN edd_required_yn       SET DEFAULT FALSE,
    ALTER COLUMN fatca_reportable_yn   SET DEFAULT FALSE,
    ALTER COLUMN crs_reportable_yn     SET DEFAULT FALSE;

-- customer
ALTER TABLE customer
    ALTER COLUMN main_customer_yn  DROP DEFAULT,
    ALTER COLUMN sms_receive_yn    DROP DEFAULT,
    ALTER COLUMN email_receive_yn  DROP DEFAULT,
    ALTER COLUMN postal_receive_yn DROP DEFAULT;
ALTER TABLE customer
    ALTER COLUMN main_customer_yn TYPE BOOLEAN
        USING (CASE WHEN main_customer_yn IS NULL THEN NULL ELSE upper(main_customer_yn) IN ('T','Y','1','TRUE') END),
    ALTER COLUMN sms_receive_yn TYPE BOOLEAN
        USING (CASE WHEN sms_receive_yn IS NULL THEN NULL ELSE upper(sms_receive_yn) IN ('T','Y','1','TRUE') END),
    ALTER COLUMN email_receive_yn TYPE BOOLEAN
        USING (CASE WHEN email_receive_yn IS NULL THEN NULL ELSE upper(email_receive_yn) IN ('T','Y','1','TRUE') END),
    ALTER COLUMN postal_receive_yn TYPE BOOLEAN
        USING (CASE WHEN postal_receive_yn IS NULL THEN NULL ELSE upper(postal_receive_yn) IN ('T','Y','1','TRUE') END);
ALTER TABLE customer
    ALTER COLUMN main_customer_yn  SET DEFAULT FALSE,
    ALTER COLUMN sms_receive_yn    SET DEFAULT FALSE,
    ALTER COLUMN email_receive_yn  SET DEFAULT FALSE,
    ALTER COLUMN postal_receive_yn SET DEFAULT FALSE;

-- 상태·등급 이력
ALTER TABLE customer_status_history
    ALTER COLUMN system_auto_triggered_yn DROP DEFAULT,
    ALTER COLUMN system_auto_triggered_yn TYPE BOOLEAN
        USING (CASE WHEN system_auto_triggered_yn IS NULL THEN NULL ELSE upper(system_auto_triggered_yn) IN ('T','Y','1','TRUE') END);
ALTER TABLE customer_status_history ALTER COLUMN system_auto_triggered_yn SET DEFAULT FALSE;

ALTER TABLE customer_grade_history
    ALTER COLUMN system_auto_triggered_yn DROP DEFAULT,
    ALTER COLUMN system_auto_triggered_yn TYPE BOOLEAN
        USING (CASE WHEN system_auto_triggered_yn IS NULL THEN NULL ELSE upper(system_auto_triggered_yn) IN ('T','Y','1','TRUE') END);
ALTER TABLE customer_grade_history ALTER COLUMN system_auto_triggered_yn SET DEFAULT FALSE;

-- 신원확인
ALTER TABLE identity_verification
    ALTER COLUMN consumed_yn DROP DEFAULT,
    ALTER COLUMN consumed_yn TYPE BOOLEAN
        USING (CASE WHEN consumed_yn IS NULL THEN NULL ELSE upper(consumed_yn) IN ('T','Y','1','TRUE') END);
ALTER TABLE identity_verification ALTER COLUMN consumed_yn SET DEFAULT FALSE;

-- 인증보안계
ALTER TABLE fds_rule
    ALTER COLUMN fds_rule_active_yn DROP DEFAULT,
    ALTER COLUMN fds_rule_active_yn TYPE BOOLEAN
        USING (CASE WHEN fds_rule_active_yn IS NULL THEN NULL ELSE upper(fds_rule_active_yn) IN ('T','Y','1','TRUE') END);
ALTER TABLE fds_rule ALTER COLUMN fds_rule_active_yn SET DEFAULT FALSE;

ALTER TABLE registered_device
    ALTER COLUMN trusted_device_yn  DROP DEFAULT,
    ALTER COLUMN designated_pc_yn   DROP DEFAULT;
ALTER TABLE registered_device
    ALTER COLUMN trusted_device_yn TYPE BOOLEAN
        USING (CASE WHEN trusted_device_yn IS NULL THEN NULL ELSE upper(trusted_device_yn) IN ('T','Y','1','TRUE') END),
    ALTER COLUMN designated_pc_yn TYPE BOOLEAN
        USING (CASE WHEN designated_pc_yn IS NULL THEN NULL ELSE upper(designated_pc_yn) IN ('T','Y','1','TRUE') END);
ALTER TABLE registered_device
    ALTER COLUMN trusted_device_yn SET DEFAULT FALSE,
    ALTER COLUMN designated_pc_yn  SET DEFAULT FALSE;

ALTER TABLE auth_method
    ALTER COLUMN primary_auth_method_yn DROP DEFAULT,
    ALTER COLUMN primary_auth_method_yn TYPE BOOLEAN
        USING (CASE WHEN primary_auth_method_yn IS NULL THEN NULL ELSE upper(primary_auth_method_yn) IN ('T','Y','1','TRUE') END);
ALTER TABLE auth_method ALTER COLUMN primary_auth_method_yn SET DEFAULT FALSE;

ALTER TABLE mobile_auth
    ALTER COLUMN mobile_auth_verified_yn DROP DEFAULT,
    ALTER COLUMN mobile_auth_verified_yn TYPE BOOLEAN
        USING (CASE WHEN mobile_auth_verified_yn IS NULL THEN NULL ELSE upper(mobile_auth_verified_yn) IN ('T','Y','1','TRUE') END);
ALTER TABLE mobile_auth ALTER COLUMN mobile_auth_verified_yn SET DEFAULT FALSE;

ALTER TABLE login_attempt
    ALTER COLUMN login_attempt_success_yn DROP DEFAULT,
    ALTER COLUMN login_attempt_success_yn TYPE BOOLEAN
        USING (CASE WHEN login_attempt_success_yn IS NULL THEN NULL ELSE upper(login_attempt_success_yn) IN ('T','Y','1','TRUE') END);
ALTER TABLE login_attempt ALTER COLUMN login_attempt_success_yn SET DEFAULT FALSE;

ALTER TABLE login_session
    ALTER COLUMN session_mfa_completed_yn DROP DEFAULT,
    ALTER COLUMN session_mfa_completed_yn TYPE BOOLEAN
        USING (CASE WHEN session_mfa_completed_yn IS NULL THEN NULL ELSE upper(session_mfa_completed_yn) IN ('T','Y','1','TRUE') END);
ALTER TABLE login_session ALTER COLUMN session_mfa_completed_yn SET DEFAULT FALSE;

ALTER TABLE fds_incident
    ALTER COLUMN fds_incident_fss_reported_yn DROP DEFAULT,
    ALTER COLUMN fds_incident_fss_reported_yn TYPE BOOLEAN
        USING (CASE WHEN fds_incident_fss_reported_yn IS NULL THEN NULL ELSE upper(fds_incident_fss_reported_yn) IN ('T','Y','1','TRUE') END);
ALTER TABLE fds_incident ALTER COLUMN fds_incident_fss_reported_yn SET DEFAULT FALSE;

-- -----------------------------------------------------------------------------
-- 4) 생성 컬럼을 불린으로 복구한다.
--
-- 원본은 네 컬럼을 각각 = 'T' 와 비교하고 CASE 로 'T'/'F' 를 만들어야 했다.
-- 불린이 되면 식이 뜻 그대로가 된다.
--
-- 컬럼 순서가 테이블 끝으로 밀린다. JPA·MyBatis 는 이름으로 접근하므로 영향이 없지만,
-- SELECT * 의 컬럼 순서에 기대는 코드가 있다면 깨진다(레포에는 없다).
-- -----------------------------------------------------------------------------
ALTER TABLE compliance_info
    ADD COLUMN is_sanctioned_yn BOOLEAN GENERATED ALWAYS AS (
        is_ofac_sanctioned_yn OR is_un_sanctioned_yn OR is_eu_sanctioned_yn OR is_kr_sanctioned_yn
    ) STORED;

-- -----------------------------------------------------------------------------
-- 5) 업무 규칙 CHECK 를 불린 형태로 복구한다.
--
-- 원본(V1): (is_pep_yn = 'T' AND pep_type_code IS NOT NULL)
--        OR (is_pep_yn = 'F' AND pep_type_code IS NULL AND pep_country_code IS NULL)
--
-- 불린이 되면 = 'T' 비교가 필요 없어져 규칙이 읽는 그대로가 된다.
-- -----------------------------------------------------------------------------
ALTER TABLE party_person
    ADD CONSTRAINT chk_party_person_pep CHECK (
        (is_pep_yn AND pep_type_code IS NOT NULL)
        OR (NOT is_pep_yn AND pep_type_code IS NULL AND pep_country_code IS NULL)
    );
