-- 격리 해제 — AI 감사가 격리한 리포트를 사람이 처분할 수 있게 한다.
--
-- V23__quarantine_signal.sql 이 격리로 **들어가는** 길을 만들었다. 나오는 길은 없었다.
-- AI 감사(AuditFairnessAgent)가 BIAS_SUSPECTED·VIOLATION_SUSPECTED 로 판정하면
-- advr_status_cd 가 QUARANTINE 이 되는데, 그것을 되돌리는 API 가 없어 영구 격리였다.
-- 관리자 화면에 "재심사 배정 · 감사부 조사 의뢰 · 정상 판정(격리 해제)" 버튼이
-- 있었지만 셋 다 아무 데도 연결돼 있지 않았다.
--
-- README 가 "agents propose, humans dispose" 라고 말한다. propose 는 되고 있었고
-- dispose 가 없었다. 이 마이그레이션이 그 자리를 만든다.
--
-- **처분은 상태만 바꾸지 않는다.** 격리를 푼 사실 자체가 감사 대상이라, 누가 왜
-- 풀었는지를 남기지 않으면 통제가 아니라 그냥 상태 변경이다.

ALTER TABLE review_advisory_report
    ADD COLUMN IF NOT EXISTS quarantine_disposition_cd   VARCHAR(50),
    ADD COLUMN IF NOT EXISTS quarantine_disposed_at      TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS quarantine_disposed_by      BIGINT,
    ADD COLUMN IF NOT EXISTS quarantine_disposition_note TEXT;

COMMENT ON COLUMN review_advisory_report.quarantine_disposition_cd IS
    '격리 처분: RELEASED(정상 판정) · REVIEW_REASSIGNED(재심사 배정) · AUDIT_REFERRED(감사부 조사 의뢰)';
COMMENT ON COLUMN review_advisory_report.quarantine_disposed_by IS
    '처분한 직원. 게이트웨이가 검증한 X-User-Id 만 들어간다 — 요청 본문의 값은 쓰지 않는다.';
COMMENT ON COLUMN review_advisory_report.quarantine_disposition_note IS
    '처분 사유. 격리를 푸는 판단의 근거이므로 비워 둘 수 없다.';

-- 처분 코드는 셋뿐이다. 오타로 새 값이 들어가면 조회·집계가 조용히 어긋난다.
ALTER TABLE review_advisory_report
    DROP CONSTRAINT IF EXISTS ck_advr_quarantine_disposition;
ALTER TABLE review_advisory_report
    ADD CONSTRAINT ck_advr_quarantine_disposition
    CHECK (quarantine_disposition_cd IS NULL
           OR quarantine_disposition_cd IN ('RELEASED', 'REVIEW_REASSIGNED', 'AUDIT_REFERRED'));

-- 처분했다면 누가·언제·왜가 모두 있어야 한다. 셋 중 하나라도 비면 감사에서
-- "누가 풀었는가" 에 답할 수 없고, 그러면 컬럼이 있으나 마나다.
ALTER TABLE review_advisory_report
    DROP CONSTRAINT IF EXISTS ck_advr_quarantine_disposition_complete;
ALTER TABLE review_advisory_report
    ADD CONSTRAINT ck_advr_quarantine_disposition_complete
    CHECK (quarantine_disposition_cd IS NULL
           OR (quarantine_disposed_at IS NOT NULL
               AND quarantine_disposed_by IS NOT NULL
               AND quarantine_disposition_note IS NOT NULL
               AND length(btrim(quarantine_disposition_note)) > 0));

-- 격리 화면은 처분 이력을 최근순으로 본다.
CREATE INDEX IF NOT EXISTS ix_advr_quarantine_disposed_at
    ON review_advisory_report (quarantine_disposed_at DESC)
    WHERE quarantine_disposition_cd IS NOT NULL;
