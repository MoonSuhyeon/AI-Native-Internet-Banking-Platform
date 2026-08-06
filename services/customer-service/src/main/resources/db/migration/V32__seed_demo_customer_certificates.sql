-- =============================================================================
-- 데모 고객(user01~03)에게 금융인증서를 심는다.
--
-- 왜 지금 필요한가: 이체에 거래 승인(step-up) 인증이 붙었다. 인증서가 없으면 승인 토큰을
-- 받을 수 없어 이체가 막힌다. V4 는 직원 계정(9001)에만 인증서를 심었고 고객 계정
-- (9111~9113, V23)에는 없었다.
--
-- PIN = 123456 (V10 이 직원 시드에 쓴 것과 같은 해시). 데모 전용 공개 PIN 이므로
-- 시리얼을 DEMO-CUST-* 로 한정해 운영 데이터에 영향이 없게 한다.
-- =============================================================================

INSERT INTO auth_method (customer_id, auth_method_type_code, auth_method_status_code,
                         primary_auth_method_yn, auth_method_registered_date,
                         created_at, updated_at, version)
SELECT c.customer_id, 'CERT_FIN', 'ACTIVE', 'T', TO_CHAR(CURRENT_DATE, 'YYYYMMDD'),
       NOW(), NOW(), 0
  FROM customer c
 WHERE c.customer_id IN (9111, 9112, 9113)
   AND NOT EXISTS (
        SELECT 1 FROM auth_method am
         WHERE am.customer_id = c.customer_id
           AND am.auth_method_type_code = 'CERT_FIN'
           AND am.deleted_at IS NULL);

INSERT INTO certificate (customer_id, auth_method_id,
                         certificate_type_code, certificate_serial_number,
                         certificate_issuer_name, certificate_subject_dn, certificate_issuer_dn,
                         certificate_public_key, certificate_purpose_code,
                         certificate_issued_date, certificate_expiry_date,
                         certificate_status_code, cert_pin_hash,
                         cert_login_failure_count, max_cert_login_failure_count,
                         created_at, updated_at, version)
SELECT am.customer_id,
       am.auth_method_id,
       'CERT_FIN',
       'FINCERT-DEMO-CUST-' || am.customer_id,
       '금융결제원',
       'CN=테스트고객' || (am.customer_id - 9110) || ', OU=Personal, O=AXful Bank, C=KR',
       'CN=금융결제원CA, O=KFTC, C=KR',
       'RSA-PUBLIC-KEY-PLACEHOLDER',
       'LOGIN',
       TO_CHAR(CURRENT_DATE, 'YYYYMMDD'),
       TO_CHAR(CURRENT_DATE + INTERVAL '3 years', 'YYYYMMDD'),
       'ACTIVE',
       -- PIN 123456 (bcrypt). V10 이 직원 시드에 쓴 것과 같은 해시.
       '$2a$10$D53MtwzNYduF8dFtg9rfxuTlv5rfN7nWWX72Lu1KyWC2gZ9ep6wwC',
       0, 5,
       NOW(), NOW(), 0
  FROM auth_method am
 WHERE am.customer_id IN (9111, 9112, 9113)
   AND am.auth_method_type_code = 'CERT_FIN'
   AND am.deleted_at IS NULL
   AND NOT EXISTS (
        SELECT 1 FROM certificate ct
         WHERE ct.customer_id = am.customer_id
           AND ct.certificate_serial_number = 'FINCERT-DEMO-CUST-' || am.customer_id);
