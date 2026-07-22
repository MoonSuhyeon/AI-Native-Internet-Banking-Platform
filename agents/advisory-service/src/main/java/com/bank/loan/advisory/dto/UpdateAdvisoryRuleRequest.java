package com.bank.loan.advisory.dto;

import jakarta.validation.constraints.Pattern;

/**
 * 룰 활성화/임계치 조정 요청 — `PUT /advisory/rules/{ruleId}`.
 * null 필드는 미변경. activeYn / ruleParams / ruleVersion / effective* 만 갱신 가능.
 */
public record UpdateAdvisoryRuleRequest(
        @Pattern(regexp = "[YN]?") String activeYn,
        String ruleParams,
        String ruleVersion,
        @Pattern(regexp = "\\d{8}|^$") String effectiveStartDate,
        @Pattern(regexp = "\\d{8}|^$") String effectiveEndDate,
        String ruleDesc,
        String changeReasonCd,
        String changeRemark
) {}
