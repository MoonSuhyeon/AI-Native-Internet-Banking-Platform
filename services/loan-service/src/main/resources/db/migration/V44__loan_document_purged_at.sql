-- 신청서류 원본 파기 시각.
--
-- loan_document.retention_until 은 "언제까지 보관해야 하는가"만 담고 있고,
-- 실제로 원본을 파기했는지는 어디에도 남지 않았다. 그래서 보존기한이 지난 서류를
-- 정리하는 배치를 만들 수 없었다 — 무엇이 이미 처리됐는지 알 수 없어 매일 같은 대상을
-- 다시 훑게 되고, 파기 사실 자체도 감사에 제시할 수 없기 때문이다.
--
-- purged_at 은 원본 바이트를 스토리지에서 지운 시각이다.
-- 메타데이터(이름·해시·크기·제출시각)는 남긴다. 파기했다는 사실과 무엇을 파기했는지는
-- 보존기한과 무관하게 증빙으로 남아야 한다.
ALTER TABLE loan_document
    ADD COLUMN IF NOT EXISTS purged_at TIMESTAMPTZ;

COMMENT ON COLUMN loan_document.purged_at IS
    '원본 파일 파기 시각. NULL 이면 원본 보관 중. 메타데이터는 파기 후에도 유지된다.';

-- 배치는 "보존기한이 지났고 아직 파기되지 않은" 서류를 훑는다.
CREATE INDEX IF NOT EXISTS ix_loan_document_retention_purge
    ON loan_document (retention_until)
    WHERE purged_at IS NULL AND retention_until IS NOT NULL;
