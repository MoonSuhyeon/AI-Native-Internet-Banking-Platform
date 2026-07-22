package com.bank.loan.advisory.engine;

/**
 * 어드바이저리 룰 평가 컨텍스트.
 *   SYNC  : 대상 심사(revId) 1건
 *   BATCH : 스냅샷 기준일자(snapshotDate, YYYYMMDD) 1개에 대해 다수 심사 일괄
 *
 * 룩업이 필요한 룰은 자체 의존성(Repository 등)으로 주입받는다 — context 는 식별자만 보유.
 */
public record RuleContext(EvaluationMode mode, Long revId, String snapshotDate) {

    public static RuleContext sync(Long revId) {
        if (revId == null) {
            throw new IllegalArgumentException("SYNC 모드 RuleContext 는 revId 필수");
        }
        return new RuleContext(EvaluationMode.SYNC, revId, null);
    }

    public static RuleContext batch(String snapshotDate) {
        if (snapshotDate == null || snapshotDate.length() != 8) {
            throw new IllegalArgumentException("BATCH 모드 RuleContext 는 snapshotDate(YYYYMMDD) 필수");
        }
        return new RuleContext(EvaluationMode.BATCH, null, snapshotDate);
    }
}
