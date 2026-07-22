-- ============================================================
-- STAGE 5.6 격리(Quarantine) 신호
-- BIAS_SUSPECTED / VIOLATION_SUSPECTED 결론 시 리포트 상태를 QUARANTINE 으로 자동 전환.
-- ============================================================

ALTER TABLE review_advisory_report
    ADD COLUMN quarantined_at TIMESTAMPTZ;

CREATE INDEX idx_review_advisory_report_quarantine
    ON review_advisory_report (quarantined_at DESC)
    WHERE advr_status_cd = 'QUARANTINE' AND deleted_at IS NULL;
