package com.bank.ai.shadow.canary;

/**
 * Shadow 게이트 판정 결과 — {@link ShadowGateChecker} 반환값.
 *
 * @param passed           게이트 통과 여부
 * @param total            판정 기간 내 shadow_run_result 총 건수
 * @param agreementRate    일치율 (= 1 - diverged/total). 게이트 기준 ≥ 0.95.
 * @param citationMissRate citation 누락률 (POLICY_FLAG_DIFF / rag_enabled). 게이트 기준 ≤ 0.05.
 * @param failReason       실패 사유 문자열 (통과 시 null, INSUFFICIENT_DATA 포함)
 */
public record GateStatus(
        boolean passed,
        int total,
        double agreementRate,
        double citationMissRate,
        String failReason
) {
    /** 게이트 통과 팩토리. */
    public static GateStatus pass(int total, double agreementRate, double citationMissRate) {
        return new GateStatus(true, total, agreementRate, citationMissRate, null);
    }

    /** 게이트 실패 팩토리. */
    public static GateStatus fail(int total, double agreementRate, double citationMissRate,
                                  String reason) {
        return new GateStatus(false, total, agreementRate, citationMissRate, reason);
    }

    /** 데이터 부족 — shadow 건수가 최소치 미만. */
    public static GateStatus insufficientData(int total, int minRequired) {
        return new GateStatus(false, total, 0.0, 0.0,
                "INSUFFICIENT_DATA: got %d, need >= %d".formatted(total, minRequired));
    }
}
