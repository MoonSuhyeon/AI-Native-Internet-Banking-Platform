package com.bank.loan.advisory.controller;

import com.bank.common.persistence.CurrentActorProvider;
import com.bank.common.web.ApiResponse;
import com.bank.common.web.BusinessException;
import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.dto.AdvisoryAckRequest;
import com.bank.loan.advisory.dto.AdvisoryAckResponse;
import com.bank.loan.advisory.dto.AdvisoryReportDetailResponse;
import com.bank.loan.advisory.dto.AdvisoryReportListResponse;
import com.bank.loan.advisory.dto.AdvisoryReportSummaryResponse;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import com.bank.loan.advisory.service.AdvisoryAckService;
import com.bank.loan.advisory.service.AdvisoryAckService.AdvisoryAckCommand;
import com.bank.loan.advisory.service.AdvisoryReportListFilter;
import com.bank.loan.advisory.service.AdvisoryReportQueryService;
import com.bank.loan.advisory.service.AdvisoryRoleGuard;
import com.bank.loan.advisory.service.AdvisoryViewerRole;
import com.bank.loan.support.LoanErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@Tag(name = "어드바이저리 리포트", description = "Advisory - 리포트 조회/조회마킹/ack")
@RestController
@RequestMapping("/api/advisory/reports")
@RequiredArgsConstructor
public class AdvisoryReportController {

    private final AdvisoryReportQueryService queryService;
    private final AdvisoryAckService ackService;
    private final ReviewAdvisoryReportRepository reportRepo;
    private final CurrentActorProvider currentActor;
    private final AdvisoryRoleGuard roleGuard;

    @Operation(summary = "리포트 목록",
            description = "필터: targetReviewerId/revId/advisoryTypeCd/severityCd/advrStatusCd. " +
                          "reviewer 는 X-Actor-Role=REVIEWER 헤더로 호출 — 본인 대상 리포트만 노출.")
    @GetMapping
    public ApiResponse<AdvisoryReportListResponse> list(
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader,
            @RequestParam(required = false) Long targetReviewerId,
            @RequestParam(required = false) Long revId,
            @RequestParam(required = false) String advisoryTypeCd,
            @RequestParam(required = false) String severityCd,
            @RequestParam(required = false) String advrStatusCd) {
        Long actorId = currentActor.currentActorId();
        AdvisoryViewerRole role = roleGuard.requireAnyRole(roleHeader);
        var filter = new AdvisoryReportListFilter(targetReviewerId, revId,
                advisoryTypeCd, severityCd, advrStatusCd);
        return ApiResponse.ok(AdvisoryReportListResponse.of(
                queryService.findForViewer(actorId, role, filter)));
    }

    @Operation(summary = "리포트 상세", description = "signal + ack 이력 포함.")
    @GetMapping("/{advrId}")
    public ApiResponse<AdvisoryReportDetailResponse> get(
            @PathVariable Long advrId,
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader) {
        Long actorId = currentActor.currentActorId();
        AdvisoryViewerRole role = roleGuard.requireAnyRole(roleHeader);
        return ApiResponse.ok(queryService.getDetail(advrId, actorId, role));
    }

    @Operation(summary = "리포트 조회 마킹",
            description = "OPEN → VIEWED 전이. first_viewed_at 최초 1회만 채워진다.")
    @PostMapping("/{advrId}/view")
    public ApiResponse<AdvisoryReportSummaryResponse> view(
            @PathVariable Long advrId,
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader) {
        Long actorId = currentActor.currentActorId();
        AdvisoryViewerRole role = roleGuard.requireAnyRole(roleHeader);
        return ApiResponse.ok(queryService.markViewed(advrId, actorId, role));
    }

    @Operation(summary = "ack 등록",
            description = "ack 응답을 적재하고 리포트를 ACKED 로 전이. 같은 리포트에 여러 번 적재 가능.")
    @PostMapping("/{advrId}/ack")
    public ResponseEntity<ApiResponse<AdvisoryAckResponse>> ack(
            @PathVariable Long advrId,
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader,
            HttpServletRequest req,
            @Valid @RequestBody AdvisoryAckRequest body) {
        AdvisoryViewerRole role = roleGuard.requireAnyRole(roleHeader);
        Long actorId = currentActor.currentActorId();

        // 권한 게이트 — reviewer 는 본인 대상 리포트만 ack 가능
        ReviewAdvisoryReport report = reportRepo.findById(advrId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_190));
        if (role == AdvisoryViewerRole.REVIEWER
                && !Objects.equals(report.getTargetReviewerId(), actorId)) {
            throw new BusinessException(LoanErrorCode.LOAN_190);
        }

        var ack = ackService.acknowledge(advrId, AdvisoryAckCommand.builder()
                .ackResponseCd(body.ackResponseCd())
                .decisionChangeYn(body.decisionChangeYn())
                .ackReasonCd(body.ackReasonCd())
                .ackRemark(body.ackRemark())
                .beforeDecisionCd(body.beforeDecisionCd())
                .afterDecisionCd(body.afterDecisionCd())
                .ackReviewerId(actorId)
                .clientIp(req.getRemoteAddr())
                .device(req.getHeader("User-Agent"))
                .build());
        return ResponseEntity.status(201).body(ApiResponse.ok(AdvisoryAckResponse.of(ack)));
    }
}
