-- ============================================================
-- doc-agent Admin 화면 데모 시드 (local 프로파일 전용)
--
-- Flyway 반복 마이그레이션(R__). 이 파일이 있는 db/seed 디렉터리는
-- application-local.yml 의 flyway.locations 에서만 추가되므로
-- 운영/스테이징/docker 프로파일에서는 스캔조차 되지 않는다.
-- 버전 마이그레이션(V1~) 이후에 실행되어 스키마 존재가 보장된다.
-- 멱등: ON CONFLICT (submission_id) DO NOTHING — 재실행 안전.
-- ============================================================

-- 위변조 의심 — HOLD + PENDING 리뷰 (큐에 표시됨)
INSERT INTO loan_document_submission (
    submission_id, application_id, doc_code,
    forgery_score, verify_status, human_review_status,
    legal_hold, created_at, updated_at
) VALUES
(
    'a1b2c3d4-0001-0000-0000-000000000001',
    'DEMO-2025-001', 'DOC_01',
    0.82, 'HOLD', 'PENDING',
    FALSE, '2025-01-10 10:05:00', '2025-01-10 10:10:00'
),
(
    'a1b2c3d4-0002-0000-0000-000000000002',
    'DEMO-2025-003', 'DOC_03',
    0.71, 'HOLD', 'PENDING',
    FALSE, '2025-03-01 11:10:00', '2025-03-01 11:15:00'
),
-- 리걸홀드 + 휴먼리뷰 대기
(
    'a1b2c3d4-0003-0000-0000-000000000003',
    'DEMO-2025-003', 'DOC_02',
    0.55, 'HOLD', 'PENDING',
    TRUE,  '2025-03-02 09:00:00', '2025-03-02 09:30:00'
)
ON CONFLICT (submission_id) DO NOTHING;

-- 정상 처리 건 (큐 미표시 — 참고용)
INSERT INTO loan_document_submission (
    submission_id, application_id, doc_code,
    forgery_score, verify_status, human_review_status,
    legal_hold, created_at, updated_at
) VALUES
(
    'b2c3d4e5-0001-0000-0000-000000000001',
    'DEMO-2025-001', 'DOC_02',
    0.12, 'AUTO_PASS', 'NOT_REQUIRED',
    FALSE, '2025-01-10 10:05:00', '2025-01-10 10:08:00'
),
(
    'b2c3d4e5-0002-0000-0000-000000000002',
    'DEMO-2025-002', 'DOC_01',
    0.08, 'AUTO_PASS', 'NOT_REQUIRED',
    FALSE, '2025-02-05 09:05:00', '2025-02-05 09:07:00'
)
ON CONFLICT (submission_id) DO NOTHING;
