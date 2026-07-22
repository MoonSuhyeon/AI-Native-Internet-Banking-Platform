package com.bank.ai.agent;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What-if 시뮬레이션 단건 결과 — pre-review-agent-plan.md §4 What-if 시뮬레이션.
 *
 * @param scenario         시나리오 식별자 (예: loan_amount_reduction_20pct)
 * @param mutatedAmountKw  변경 후 신청금액 (만원)
 * @param mutatedPeriodMo  변경 후 기간 (개월)
 * @param newDecisionScore 변경 후 P(APPROVE)
 * @param newPdScore       변경 후 PD
 * @param result           risk_reduced / no_change / risk_increased
 * @param suggestion       심사원 안내 문장 (한국어)
 * @param stillViolates    변경 후에도 hard constraint 위반 여부
 */
public record SimulationResult(
        String scenario,
        @JsonProperty("mutated_amount_kw") Long mutatedAmountKw,
        @JsonProperty("mutated_period_mo") int mutatedPeriodMo,
        @JsonProperty("new_decision_score") double newDecisionScore,
        @JsonProperty("new_pd_score") double newPdScore,
        String result,
        String suggestion,
        @JsonProperty("still_violates") boolean stillViolates
) {
}
