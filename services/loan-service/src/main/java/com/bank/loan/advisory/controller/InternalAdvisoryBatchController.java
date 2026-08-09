package com.bank.loan.advisory.controller;

import com.bank.common.web.ApiResponse;
import com.bank.loan.advisory.batch.AdvisoryBatchEvaluationService;
import com.bank.loan.advisory.batch.AdvisoryBatchEvaluationService.BatchEvaluationResult;
import com.bank.loan.advisory.batch.ReviewerDecisionSnapshotService;
import com.bank.loan.advisory.batch.ReviewerDecisionSnapshotService.SnapshotRunResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "어드바이저리", description = "Advisory - 일배치 트리거 (internal)")
@RestController
@RequestMapping("/api/internal/advisory")
@RequiredArgsConstructor
@Validated
public class InternalAdvisoryBatchController {

    private final ReviewerDecisionSnapshotService snapshotService;
    private final AdvisoryBatchEvaluationService batchEvaluationService;

    @Operation(summary = "심사관 결정 스냅샷 적재",
            description = "baseDate(YYYYMMDD) 의 본심사를 코호트(EMPLOYMENT_TYPE/LOAN_PURPOSE)별로 집계해 " +
                          "REVIEWER_DECISION_SNAPSHOT 에 적재한다. 멱등 — 이미 적재된 (reviewer/date/window/dim/value) 는 skip.")
    @PostMapping("/snapshot")
    public ApiResponse<SnapshotRunResult> runSnapshot(
            @RequestParam("baseDate") @Pattern(regexp = "\\d{8}") String baseDate) {
        return ApiResponse.ok(snapshotService.runDailySnapshot(baseDate));
    }

    @Operation(summary = "어드바이저리 배치 평가",
            description = "baseDate 의 스냅샷을 적재한 뒤 활성 BATCH 룰 전체(BIAS_*, PEER_DECISION_DIVERGENCE)를 일괄 평가해 " +
                          "리포트를 발행한다. 발행된 리포트별로 AdvisoryReportPublishedEvent 가 발행되어 알림 핸들러가 통지한다.")
    @PostMapping("/batch-evaluate")
    public ApiResponse<BatchEvaluationResult> runBatchEvaluation(
            @RequestParam("baseDate") @Pattern(regexp = "\\d{8}") String baseDate) {
        return ApiResponse.ok(batchEvaluationService.runDailyBatch(baseDate));
    }
}
