package com.bank.loan.advisory.dto;

import com.bank.loan.advisory.domain.ReviewAdvisoryRule;

/** 룰 카탈로그 항목. */
public record AdvisoryRuleResponse(
        Long ruleId,
        String ruleCd,
        String ruleName,
        String advisoryTypeCd,
        String ruleCategoryCd,
        String severityCd,
        String ruleParams,
        String ruleVersion,
        String activeYn,
        String effectiveStartDate,
        String effectiveEndDate,
        String ruleDesc
) {
    public static AdvisoryRuleResponse of(ReviewAdvisoryRule r) {
        return new AdvisoryRuleResponse(
                r.getRuleId(), r.getRuleCd(), r.getRuleName(),
                r.getAdvisoryTypeCd(), r.getRuleCategoryCd(), r.getSeverityCd(),
                r.getRuleParams(), r.getRuleVersion(), r.getActiveYn(),
                r.getEffectiveStartDate(), r.getEffectiveEndDate(),
                r.getRuleDesc());
    }
}
