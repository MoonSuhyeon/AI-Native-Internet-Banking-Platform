package com.bank.loan.advisory.dto;

import java.util.List;

/** 룰 카탈로그 응답. */
public record AdvisoryRuleListResponse(int totalCount, List<AdvisoryRuleResponse> items) {
    public static AdvisoryRuleListResponse of(List<AdvisoryRuleResponse> items) {
        return new AdvisoryRuleListResponse(items.size(), items);
    }
}
