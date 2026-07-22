package com.bank.ai.review.controller;

import com.bank.ai.review.dto.AutoReviewEvaluateResponse;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.AutoReviewResponse;
import com.bank.ai.review.service.AutoReviewService;
import com.bank.ai.rule.service.RuleEngineService;
import com.bank.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "auto-review", description = "자동 대출심사 추론 + 트랙 분기")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AutoReviewController {

    private final AutoReviewService autoReviewService;
    private final RuleEngineService ruleEngineService;

    @Operation(summary = "자동심사 ML 추론 단건",
            description = "신청자 피처를 받아 APPROVE/REJECT 결정과 확률만 반환. "
                    + "트랙 분기·hard constraint 까지 포함한 종합 결과는 /evaluate 사용.")
    @PostMapping("/auto-review")
    public ApiResponse<AutoReviewResponse> review(@Valid @RequestBody AutoReviewRequest req) {
        return ApiResponse.ok(autoReviewService.review(req));
    }

    @Operation(summary = "자동심사 종합 평가 (ML + RuleEngine)",
            description = "ML PD score + hard constraint + PolicyMatrix lookup 으로 "
                    + "Track 1(자동승인) / Track 2(자동반려) / Track 3(사람심사) 분기 결과 반환.")
    @PostMapping("/auto-review/evaluate")
    public ApiResponse<AutoReviewEvaluateResponse> evaluate(@Valid @RequestBody AutoReviewRequest req) {
        return ApiResponse.ok(ruleEngineService.evaluate(req));
    }
}
