package com.bank.loan.advisory.controller;

import com.bank.common.web.ApiResponse;
import com.bank.loan.advisory.dto.AiAuditOpinionResponse;
import com.bank.loan.advisory.dto.QuarantineDispositionRequest;
import com.bank.loan.advisory.dto.QuarantineReportResponse;
import com.bank.loan.advisory.dto.ReviewerRiskScoreResponse;
import com.bank.loan.advisory.service.AdvisoryRoleGuard;
import com.bank.loan.advisory.service.AuditOpinionQueryService;
import com.bank.loan.advisory.service.QuarantineDispositionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "감사 의견 / 위험도 스코어", description = "Advisory - LLM 감사 의견 + 심사관 위험도 조회 (auditor/admin)")
@RestController
@RequestMapping("/api/advisory/audit")
@RequiredArgsConstructor
public class AuditOpinionController {

    private final AuditOpinionQueryService queryService;
    private final QuarantineDispositionService dispositionService;
    private final AdvisoryRoleGuard roleGuard;

    @Operation(summary = "리포트별 LLM 감사 의견 조회",
            description = "advrId 에 연결된 AI 감사 의견 전체 반환 (BIAS/COMPLIANCE 각 1건). auditor/admin 권한.")
    @GetMapping("/opinions/by-report/{advrId}")
    public ApiResponse<List<AiAuditOpinionResponse>> opinionsByReport(
            @PathVariable Long advrId) {
        roleGuard.requireAuditorOrAdmin();
        return ApiResponse.ok(queryService.opinionsByAdvr(advrId));
    }

    @Operation(summary = "심사관별 LLM 감사 의견 이력",
            description = "reviewerId 의 전체 감사 의견을 최신순 반환. auditor/admin 권한.")
    @GetMapping("/opinions/by-reviewer/{reviewerId}")
    public ApiResponse<List<AiAuditOpinionResponse>> opinionsByReviewer(
            @PathVariable Long reviewerId) {
        roleGuard.requireAuditorOrAdmin();
        return ApiResponse.ok(queryService.opinionsByReviewer(reviewerId));
    }

    @Operation(summary = "심사관 위험도 스코어 조회",
            description = "reviewerId 의 bias_score / compliance_score 반환. auditor/admin 권한.")
    @GetMapping("/risk-scores/{reviewerId}")
    public ApiResponse<ReviewerRiskScoreResponse> riskScore(
            @PathVariable Long reviewerId) {
        roleGuard.requireAuditorOrAdmin();
        return ApiResponse.ok(queryService.riskScore(reviewerId));
    }

    @Operation(summary = "최근 LLM 감사 의견 목록",
            description = "생성 시각 최신순. limit 기본 20, 최대 100. auditor/admin 권한.")
    @GetMapping("/opinions/recent")
    public ApiResponse<List<AiAuditOpinionResponse>> recentOpinions(
            @RequestParam(defaultValue = "20") int limit) {
        roleGuard.requireAuditorOrAdmin();
        return ApiResponse.ok(queryService.recentOpinions(limit));
    }

    @Operation(summary = "편향 위험도 상위 심사관 목록",
            description = "bias_score 내림차순. limit 기본 20, 최대 100. auditor/admin 권한.")
    @GetMapping("/risk-scores/top/bias")
    public ApiResponse<List<ReviewerRiskScoreResponse>> topBias(
            @RequestParam(defaultValue = "20") int limit) {
        roleGuard.requireAuditorOrAdmin();
        return ApiResponse.ok(queryService.topByBias(limit));
    }

    @Operation(summary = "규정 위반 위험도 상위 심사관 목록",
            description = "compliance_score 내림차순. limit 기본 20, 최대 100. auditor/admin 권한.")
    @GetMapping("/risk-scores/top/compliance")
    public ApiResponse<List<ReviewerRiskScoreResponse>> topCompliance(
            @RequestParam(defaultValue = "20") int limit) {
        roleGuard.requireAuditorOrAdmin();
        return ApiResponse.ok(queryService.topByCompliance(limit));
    }

    @Operation(summary = "격리(Quarantine) 리포트 목록",
            description = "AI 감사 결론이 BIAS_SUSPECTED 또는 VIOLATION_SUSPECTED 인 리포트. quarantined_at 최신순. auditor/admin 권한.")
    @GetMapping("/quarantine")
    public ApiResponse<List<QuarantineReportResponse>> quarantinedReports() {
        roleGuard.requireAuditorOrAdmin();
        return ApiResponse.ok(queryService.quarantinedReports());
    }

    /**
     * 격리 처분.
     *
     * <p>이 자리가 없어서 격리가 영구였다. 조회만 있고 되돌리는 길이 없었다.
     *
     * <p>행위자는 본문에서 받지 않는다 — 게이트웨이가 검증한 신원만 쓴다.
     */
    @Operation(summary = "격리 처분",
            description = "RELEASED(정상 판정) · REVIEW_REASSIGNED(재심사 배정) · "
                    + "AUDIT_REFERRED(감사부 조사 의뢰). 사유 필수. auditor/admin 권한. "
                    + "격리 상태에서 한 번만 가능하며, 이미 처분됐으면 409.")
    @PostMapping("/quarantine/{advrId}/disposition")
    public ApiResponse<QuarantineReportResponse> disposeQuarantine(
            @PathVariable Long advrId,
            @Valid @RequestBody QuarantineDispositionRequest request) {
        return ApiResponse.ok(dispositionService.dispose(advrId, request));
    }
}
