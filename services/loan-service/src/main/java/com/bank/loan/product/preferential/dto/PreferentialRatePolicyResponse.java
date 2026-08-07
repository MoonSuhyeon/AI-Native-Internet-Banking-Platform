package com.bank.loan.product.preferential.dto;

import com.bank.loan.product.preferential.domain.PreferentialRatePolicy;

public record PreferentialRatePolicyResponse(
        Long policyId,
        Long prodId,
        String policyName,
        String conditionCd,
        Integer preferentialRateBps,
        Integer maxStackBps,
        Boolean activeYn,
        String effectiveStartDate,
        String effectiveEndDate,
        String policyRemark
) {
    public static PreferentialRatePolicyResponse of(PreferentialRatePolicy p) {
        return new PreferentialRatePolicyResponse(
                p.getPolicyId(), p.getProdId(), p.getPolicyName(), p.getConditionCd(),
                p.getPreferentialRateBps(), p.getMaxStackBps(), p.getActiveYn(),
                p.getEffectiveStartDate(), p.getEffectiveEndDate(), p.getPolicyRemark()
        );
    }
}
