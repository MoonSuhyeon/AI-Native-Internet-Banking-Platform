package com.bank.loan.advisory.controller;

import com.bank.common.web.ApiResponse;
import com.bank.loan.advisory.dto.AdvisoryRuleListResponse;
import com.bank.loan.advisory.dto.AdvisoryRuleResponse;
import com.bank.loan.advisory.dto.UpdateAdvisoryRuleRequest;
import com.bank.loan.advisory.service.AdvisoryRoleGuard;
import com.bank.loan.advisory.service.AdvisoryRuleAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "어드바이저리 룰", description = "Advisory - 룰 카탈로그 조회/변경 (admin)")
@RestController
@RequestMapping("/api/advisory/rules")
@RequiredArgsConstructor
public class AdvisoryRuleController {

    private final AdvisoryRuleAdminService adminService;
    private final AdvisoryRoleGuard roleGuard;

    @Operation(summary = "룰 카탈로그 조회", description = "활성/비활성 룰 전체. admin/auditor 권한.")
    @GetMapping
    public ApiResponse<AdvisoryRuleListResponse> list(
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader) {
        roleGuard.requireAuditorOrAdmin(roleHeader);
        return ApiResponse.ok(AdvisoryRuleListResponse.of(adminService.listAll()));
    }

    @Operation(summary = "룰 활성화/임계치 조정",
            description = "active_yn, rule_params, rule_version, effective 일자, rule_desc 변경. " +
                          "변경 시 STATUS_HISTORY 에 BEFORE/AFTER 스냅샷 적재. admin 만.")
    @PutMapping("/{ruleId}")
    public ApiResponse<AdvisoryRuleResponse> update(
            @PathVariable Long ruleId,
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader,
            @Valid @RequestBody UpdateAdvisoryRuleRequest body) {
        roleGuard.requireAdmin(roleHeader);
        return ApiResponse.ok(adminService.update(ruleId, body));
    }
}
