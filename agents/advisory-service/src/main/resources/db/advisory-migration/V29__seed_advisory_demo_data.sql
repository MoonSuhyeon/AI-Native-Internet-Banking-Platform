-- ============================================================
-- V29: 어드바이저리 도메인 데모 시드 데이터
--
-- 배경: advisory 테이블(review_advisory_rule / review_advisory_report /
--       ai_audit_opinion / advisory_document)은 advisory 전용 Flyway
--       (AdvisoryFlywayConfig, 이력 테이블 advisory_flyway_schema_history)가
--       loan 기본 Flyway(db/migration) '이후'에 생성한다.
--       따라서 이들 테이블에 대한 시드를 loan 스트림(V35/V37/V38)에 두면
--       테이블이 아직 없는 시점에 INSERT 가 실행돼 부팅이 실패한다
--       (relation "review_advisory_rule" does not exist).
--       시드를 advisory 스트림으로 옮겨 테이블 생성 이후에 적재한다.
-- 전제: 본 마이그레이션은 loan 기본 Flyway 완료 후 실행되므로
--       loan_review(rev_id=9003/9007/9103) 등 참조 대상이 이미 존재한다.
-- 멱등: 모든 INSERT 는 ON CONFLICT ... DO NOTHING 또는 WHERE NOT EXISTS 사용.
-- 참조: customer_id=1001~1003 은 customer-service 에 등록된 가상 고객.
--       created_by=0 은 시스템(migration) 행위자.
-- ============================================================

-- ------------------------------------------------------------
-- 1. 어드바이저리 규칙 (review_advisory_rule)
--    AdvisoryRuleSeeder 가 앱 기동 시 동일 rule_cd 를 삽입하므로,
--    rule_cd 가 이미 존재하면 skip.
-- ------------------------------------------------------------
INSERT INTO review_advisory_rule (
    rule_cd, rule_name, advisory_type_cd, rule_category_cd, severity_cd,
    rule_params, rule_version, active_yn, rule_desc,
    created_at, created_by, updated_at, updated_by, version
)
SELECT 'DSR_THRESHOLD_OVERRIDE', 'DSR 한도 초과 승인',
       'REREVIEW_RECOMMEND', 'THRESHOLD_VIOLATION', 'CRITICAL',
       NULL, 'v1.0', 'Y', 'DSR_CALCULATION.dsr_status_cd=FAIL 인데 본심사가 승인된 경우',
       '2025-01-01 00:00:00+09', 0, '2025-01-01 00:00:00+09', 0, 0
WHERE NOT EXISTS (
    SELECT 1 FROM review_advisory_rule WHERE rule_cd='DSR_THRESHOLD_OVERRIDE' AND deleted_at IS NULL
);

INSERT INTO review_advisory_rule (
    rule_cd, rule_name, advisory_type_cd, rule_category_cd, severity_cd,
    rule_params, rule_version, active_yn, rule_desc,
    created_at, created_by, updated_at, updated_by, version
)
SELECT 'LTV_THRESHOLD_OVERRIDE', 'LTV 한도 초과 승인',
       'REREVIEW_RECOMMEND', 'THRESHOLD_VIOLATION', 'CRITICAL',
       NULL, 'v1.0', 'Y', 'LTV_CALCULATION.ltv_status_cd=FAIL 인데 본심사가 승인된 경우',
       '2025-01-01 00:00:00+09', 0, '2025-01-01 00:00:00+09', 0, 0
WHERE NOT EXISTS (
    SELECT 1 FROM review_advisory_rule WHERE rule_cd='LTV_THRESHOLD_OVERRIDE' AND deleted_at IS NULL
);

INSERT INTO review_advisory_rule (
    rule_cd, rule_name, advisory_type_cd, rule_category_cd, severity_cd,
    rule_params, rule_version, active_yn, rule_desc,
    created_at, created_by, updated_at, updated_by, version
)
SELECT 'BIAS_REJECT_RATE_DEVIATION', '심사관 거절율 편차',
       'BIAS_DETECTION', 'REVIEWER_DEVIATION', 'WARN',
       NULL, 'v1.0', 'Y', '코호트 거절율이 동료 평균 대비 +2σ 초과 (최소 표본 30)',
       '2025-01-01 00:00:00+09', 0, '2025-01-01 00:00:00+09', 0, 0
WHERE NOT EXISTS (
    SELECT 1 FROM review_advisory_rule WHERE rule_cd='BIAS_REJECT_RATE_DEVIATION' AND deleted_at IS NULL
);

INSERT INTO review_advisory_rule (
    rule_cd, rule_name, advisory_type_cd, rule_category_cd, severity_cd,
    rule_params, rule_version, active_yn, rule_desc,
    created_at, created_by, updated_at, updated_by, version
)
SELECT 'BIAS_APPROVAL_RATE_DEVIATION', '심사관 승인율 편차',
       'BIAS_DETECTION', 'REVIEWER_DEVIATION', 'WARN',
       NULL, 'v1.0', 'Y', '코호트 승인율이 동료 평균 대비 -2σ 미만 (최소 표본 30)',
       '2025-01-01 00:00:00+09', 0, '2025-01-01 00:00:00+09', 0, 0
WHERE NOT EXISTS (
    SELECT 1 FROM review_advisory_rule WHERE rule_cd='BIAS_APPROVAL_RATE_DEVIATION' AND deleted_at IS NULL
);

INSERT INTO review_advisory_rule (
    rule_cd, rule_name, advisory_type_cd, rule_category_cd, severity_cd,
    rule_params, rule_version, active_yn, rule_desc,
    created_at, created_by, updated_at, updated_by, version
)
SELECT 'PEER_DECISION_DIVERGENCE', '유사 신청자 결정 분기',
       'REREVIEW_RECOMMEND', 'PEER_DIVERGENCE', 'WARN',
       NULL, 'v1.0', 'Y', '유사 프로파일(신용±5점/DSR±500/LTV±500) 90일 그룹 70:30 분기에서 본 건이 소수 결정',
       '2025-01-01 00:00:00+09', 0, '2025-01-01 00:00:00+09', 0, 0
WHERE NOT EXISTS (
    SELECT 1 FROM review_advisory_rule WHERE rule_cd='PEER_DECISION_DIVERGENCE' AND deleted_at IS NULL
);

-- ------------------------------------------------------------
-- 2. 어드바이저리 리포트 (review_advisory_report)
--    advr_id=9001: rev_id=9003 (V35) — DSR 한도 초과 승인 자문
--    advr_id=9002: rev_id=9007 (V37) — 유사 신청자 결정 분기 자문
--    advr_id=9102: rev_id=9103 (V38) — 유사 신청자 결정 분기 자문
--    rule_id 는 subquery 로 조회 (위 1번 시드 또는 앱시더가 삽입한 행 참조).
-- ------------------------------------------------------------
INSERT INTO review_advisory_report (
    advr_id, rev_id, rule_id,
    advisory_type_cd, severity_cd, advr_status_cd,
    advr_title, advr_summary, advr_payload,
    target_reviewer_id, generated_at,
    created_at, created_by, updated_at, updated_by, version
)
OVERRIDING SYSTEM VALUE
SELECT
    9001, 9003, r.rule_id,
    'REREVIEW_RECOMMEND', 'CRITICAL', 'OPEN',
    'DSR 한도 초과 승인 감지',
    'dsr_ratio_bps=3833 이 한도(4000bps)에 근접하나 ceval_decision=APPROVED 로 처리됨. 재심사 권고.',
    '{"dsrRatioBps":3833,"dsrLimitBps":4000,"cevalGrade":"C","cevalScore":650}',
    9002, '2025-03-05 09:00:00+09',
    '2025-03-05 09:00:00+09', 0, '2025-03-05 09:00:00+09', 0, 0
FROM review_advisory_rule r
WHERE r.rule_cd = 'DSR_THRESHOLD_OVERRIDE' AND r.deleted_at IS NULL
LIMIT 1
ON CONFLICT (advr_id) DO NOTHING;

INSERT INTO review_advisory_report (
    advr_id, rev_id, rule_id,
    advisory_type_cd, severity_cd, advr_status_cd,
    advr_title, advr_summary, advr_payload,
    target_reviewer_id, generated_at,
    created_at, created_by, updated_at, updated_by, version
)
OVERRIDING SYSTEM VALUE
SELECT
    9002, 9007, r.rule_id,
    'REREVIEW_RECOMMEND', 'WARN', 'OPEN',
    '유사 신청자 결정 분기 감지',
    '유사 프로파일 90일 그룹이 70:30 으로 분기하며 본 건이 소수 결정 측에 속함. 재검토 권고.',
    '{"cohortApprovalRate":0.30,"peerSample":34,"cevalGrade":"B","cevalScore":710}',
    9002, '2025-04-07 09:00:00+09',
    '2025-04-07 09:00:00+09', 0, '2025-04-07 09:00:00+09', 0, 0
FROM review_advisory_rule r
WHERE r.rule_cd = 'PEER_DECISION_DIVERGENCE' AND r.deleted_at IS NULL
LIMIT 1
ON CONFLICT (advr_id) DO NOTHING;

INSERT INTO review_advisory_report (
    advr_id, rev_id, rule_id,
    advisory_type_cd, severity_cd, advr_status_cd,
    advr_title, advr_summary, advr_payload,
    target_reviewer_id, generated_at,
    created_at, created_by, updated_at, updated_by, version
)
OVERRIDING SYSTEM VALUE
SELECT
    9102, 9103, r.rule_id,
    'REREVIEW_RECOMMEND', 'WARN', 'OPEN',
    '유사 신청자 결정 분기 감지',
    '유사 프로파일 90일 그룹이 70:30 으로 분기하며 본 건이 소수 결정 측에 속함. 재검토 권고.',
    '{"cohortApprovalRate":0.30,"peerSample":34,"cevalGrade":"B","cevalScore":710}',
    9002, '2025-04-07 09:00:00+09',
    '2025-04-07 09:00:00+09', 0, '2025-04-07 09:00:00+09', 0, 0
FROM review_advisory_rule r
WHERE r.rule_cd = 'PEER_DECISION_DIVERGENCE' AND r.deleted_at IS NULL
LIMIT 1
ON CONFLICT (advr_id) DO NOTHING;

-- ------------------------------------------------------------
-- 3. AI 감사 의견 (ai_audit_opinion)
--    advr_id=9001 에 대한 편향 감지/컴플라이언스 의견.
-- ------------------------------------------------------------
INSERT INTO ai_audit_opinion (
    opinion_id, advr_id, rev_id, reviewer_id,
    analysis_type_cd, conclusion_cd,
    reasoning_summary, confidence_score,
    input_tokens, output_tokens, generated_at
) OVERRIDING SYSTEM VALUE VALUES
(9001, 9001, 9003, 9002,
 'BIAS_DETECTION', 'NO_BIAS_DETECTED',
 'DSR가 한도에 근접하나 심사관의 승인 결정은 신용등급(C) 및 고용안정성을 감안한 합리적 판단으로 보임. 통계적 편향 신호 미탐지.',
 0.7800,
 512, 128, '2025-03-05 09:05:00+09'),
(9002, 9001, 9003, 9002,
 'COMPLIANCE_VERIFICATION', 'COMPLIANT',
 '내부 여신심사 기준서 §3.2(DSR 예외 승인 요건) 충족. 예외 사유 기재 확인.',
 0.8500,
 480, 96, '2025-03-05 09:06:00+09')
ON CONFLICT (opinion_id) DO NOTHING;

-- ------------------------------------------------------------
-- 4. Advisory RAG 정책문서 (advisory_document)
--    임베딩(vector) 없이 마스터 행만 삽입.
--    실제 임베딩/청크는 앱 기동 후 /api/internal/advisory/documents/{id}/activate 호출.
-- ------------------------------------------------------------
INSERT INTO advisory_document (
    doc_id, doc_cd, doc_title, doc_category_cd, doc_version,
    effective_start_date, effective_end_date,
    active_yn, doc_desc,
    created_at, created_by, updated_at, updated_by, version
) OVERRIDING SYSTEM VALUE VALUES
(9001, 'POL_DSR_EXCEPTION_V1', 'DSR 예외 승인 기준서', 'POLICY', '1.0',
 '20250101', NULL,
 'N', 'DSR 한도 초과 건에 대한 예외 승인 요건 및 절차',
 '2025-01-01 00:00:00+09', 0, '2025-01-01 00:00:00+09', 0, 0),
(9002, 'POL_BIAS_REVIEW_V1', '심사 공정성 관리 지침', 'POLICY', '1.0',
 '20250101', NULL,
 'N', '심사관 편향 감지 기준 및 자문 처리 절차',
 '2025-01-01 00:00:00+09', 0, '2025-01-01 00:00:00+09', 0, 0),
(9003, 'POL_CREDIT_GRADE_V2', '신용등급별 여신한도 기준표', 'POLICY', '2.0',
 '20240701', NULL,
 'N', '등급(A~F)별 최대 여신한도 및 금리 가산 기준',
 '2025-01-01 00:00:00+09', 0, '2025-01-01 00:00:00+09', 0, 0)
ON CONFLICT (doc_id) DO NOTHING;

-- ============================================================
-- 끝.
-- ============================================================
