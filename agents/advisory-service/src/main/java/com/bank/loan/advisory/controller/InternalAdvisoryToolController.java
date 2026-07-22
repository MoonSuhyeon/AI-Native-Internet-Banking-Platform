package com.bank.loan.advisory.controller;

import com.bank.common.web.ApiResponse;
import com.bank.loan.advisory.dto.CohortStatsResponse;
import com.bank.loan.advisory.dto.PolicyCitationResponse;
import com.bank.loan.advisory.dto.ReviewerHistoryResponse;
import com.bank.loan.advisory.dto.SimilarCaseResponse;
import com.bank.loan.advisory.service.AdvisoryToolQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "어드바이저리", description = "Advisory - LLM tool-use 전용 조회 (internal)")
@RestController
@RequestMapping("/api/internal/advisory")
@RequiredArgsConstructor
public class InternalAdvisoryToolController {

    private final AdvisoryToolQueryService service;

    @Operation(summary = "정책 인용 검색",
            description = "query 텍스트로 활성 정책 문서 청크를 벡터 검색한다. LLM get_policy_citation tool 전용.")
    @GetMapping("/policy-citations")
    public ApiResponse<PolicyCitationResponse> getPolicyCitations(
            @RequestParam String query) {
        return ApiResponse.ok(service.queryCitations(query));
    }

    @Operation(summary = "심사관 결정 이력 조회",
            description = "최근 N일간 심사관 결정 이력(승인·거절 건수, 승인율)을 반환한다. LLM get_reviewer_history tool 전용.")
    @GetMapping("/reviewer-history")
    public ApiResponse<ReviewerHistoryResponse> getReviewerHistory(
            @RequestParam Long reviewerId,
            @RequestParam(defaultValue = "90") int days) {
        return ApiResponse.ok(service.queryReviewerHistory(reviewerId, days));
    }

    @Operation(summary = "유사 과거 사례 검색 (rev_id 기준)",
            description = "rev_id 에 연결된 최신 리포트 기준으로 유사 프로파일 종결 사례를 반환한다. LLM get_similar_cases tool 전용.")
    @GetMapping("/similar-cases")
    public ApiResponse<SimilarCaseResponse> getSimilarCases(
            @RequestParam Long revId,
            @RequestParam(defaultValue = "5") int topK) {
        return ApiResponse.ok(service.querySimilarCasesByRevId(revId, topK));
    }

    @Operation(summary = "코호트 편향 통계 조회",
            description = "특정 코호트의 최근 스냅샷 승인·거절률 통계를 반환한다. LLM get_cohort_stats tool 전용.")
    @GetMapping("/cohort-stats")
    public ApiResponse<CohortStatsResponse> getCohortStats(
            @RequestParam String dimension,
            @RequestParam String value) {
        return ApiResponse.ok(service.queryCohortStats(dimension, value));
    }
}
