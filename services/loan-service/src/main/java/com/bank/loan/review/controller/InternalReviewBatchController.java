package com.bank.loan.review.controller;

import com.bank.common.web.ApiResponse;
import com.bank.loan.review.dto.AiReviewAdviceResponse;
import com.bank.loan.review.dto.BiasOpsNoteRequest;
import com.bank.loan.review.dto.ExpireBiasReviewingResponse;
import com.bank.loan.review.dto.ExpirePendingApproverResponse;
import com.bank.loan.review.dto.ExpirePendingReviewsResponse;
import com.bank.loan.review.service.LoanReviewAutoDecideService;
import com.bank.loan.review.service.LoanReviewOpsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "본심사 배치", description = "InternalReviewBatch - 본심사 관련 운영 배치 endpoint")
@RestController
@RequestMapping("/api/internal/loan-reviews")
@RequiredArgsConstructor
@Validated
public class InternalReviewBatchController {

    private final LoanReviewAutoDecideService service;
    private final LoanReviewOpsService opsService;

    @Operation(summary = "권고 만료 배치",
            description = "reviewedAt 이 olderThanDays 일 이전인 PENDING_APPROVAL 본심사를 일괄 EXPIRED 로 전이. "
                    + "기본값 7일. 만료된 권고는 신청 상태에 영향 없음(PRESCREENED 유지) — "
                    + "운영자가 별도 수동 본심사 등으로 처리.")
    @PostMapping("/expire-pending")
    public ApiResponse<ExpirePendingReviewsResponse> expirePending(
            @RequestParam(defaultValue = "7") @Min(0) int olderThanDays) {
        return ApiResponse.ok(service.expirePending(olderThanDays));
    }

    @Operation(summary = "편향 검증 만료 배치",
            description = "reviewedAt 이 olderThanDays 일 이전인 BIAS_REVIEWING 본심사를 일괄 EXPIRED 로 전이. "
                    + "기본값 14일. 만료된 건의 신청 상태는 PRESCREENED 유지 — 별도 재심사 필요.")
    @PostMapping("/expire-bias-reviewing")
    public ApiResponse<ExpireBiasReviewingResponse> expireBiasReviewing(
            @RequestParam(defaultValue = "14") @Min(0) int olderThanDays) {
        return ApiResponse.ok(opsService.expireBiasReviewing(olderThanDays));
    }

    @Operation(summary = "승인자 대기 만료 배치",
            description = "pendingApproverSince 가 olderThanDays 일 이전인 PENDING_APPROVER 본심사를 일괄 EXPIRED 로 전이. "
                    + "기본값 7일. 만료된 건의 신청 상태는 PRESCREENED 유지 — 별도 재심사 필요. "
                    + "승인자 무응답으로 인한 심사 영구 블로킹 방지 안전망.")
    @PostMapping("/expire-pending-approver")
    public ApiResponse<ExpirePendingApproverResponse> expirePendingApprover(
            @RequestParam(defaultValue = "7") @Min(0) int olderThanDays) {
        return ApiResponse.ok(opsService.expirePendingApprover(olderThanDays));
    }

    @Operation(summary = "편향 운영 노트 주입",
            description = "운영자가 BIAS_REVIEWING 건에 severity=NONE 운영 노트를 삽입. "
                    + "biasSeverityCd 를 NONE 으로 갱신하여 BLOCKED 차단을 해제. "
                    + "4-eye 우회 권한 없음 — 심사원이 acknowledge 후 승인자 단계로 진행.")
    @PostMapping("/{revId}/bias-ops-note")
    public ApiResponse<AiReviewAdviceResponse> addBiasOpsNote(
            @PathVariable Long revId,
            @Valid @RequestBody BiasOpsNoteRequest req) {
        return ApiResponse.ok(opsService.addBiasOpsNote(revId, req));
    }
}
