package com.bank.loan.advisory.controller;

import com.bank.common.persistence.CurrentActorProvider;
import com.bank.common.web.ApiResponse;
import com.bank.loan.advisory.dto.PolicyCitationResponse;
import com.bank.loan.advisory.dto.SimilarCaseResponse;
import com.bank.loan.advisory.rag.PolicyCitationRetriever;
import com.bank.loan.advisory.rag.SimilarCaseRetriever;
import com.bank.loan.advisory.service.AdvisoryRagQueryService;
import com.bank.loan.advisory.service.AdvisoryRoleGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 외부 API (plan §11.5 — Task 6-8).
 *
 * 권한: reviewer/auditor/admin (AdvisoryRoleGuard).
 * reviewer 는 자신 대상 리포트만 — SimilarCaseRetriever / PolicyCitationRetriever 내부에서
 * LOAN_190 throw (report 접근 시 reviewerId 체크).
 */
@Tag(name = "어드바이저리 RAG", description = "Advisory - 유사 사례 검색 / 정책 인용 조회")
@RestController
@RequestMapping("/api/advisory/reports")
@RequiredArgsConstructor
public class AdvisoryRagController {

    private final SimilarCaseRetriever    similarCaseRetriever;
    private final PolicyCitationRetriever citationRetriever;
    private final AdvisoryRagQueryService ragQueryService;
    private final CurrentActorProvider    currentActor;
    private final AdvisoryRoleGuard       roleGuard;

    @Operation(summary = "유사 과거 사례 검색",
            description = "본 리포트와 유사 프로파일의 종결된 과거 심사 top-5 반환. " +
                          "reviewer 는 X-Actor-Role=REVIEWER 헤더 필수 — 본인 대상 리포트만 허용.")
    @GetMapping("/{advrId}/similar-cases")
    public ApiResponse<SimilarCaseResponse> similarCases(
            @PathVariable Long advrId,
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader,
            @RequestParam(defaultValue = "5") int topK) {
        roleGuard.requireAnyRole(roleHeader);
        Long actorId = currentActor.currentActorId();
        return ApiResponse.ok(similarCaseRetriever.retrieve(advrId, topK, actorId));
    }

    @Operation(summary = "정책 인용 조회",
            description = "CRITICAL 룰 발화 시 advr_payload 에 자동 적재된 정책 인용 목록을 반환한다. " +
                          "인용이 없으면 citations=[] 반환.")
    @GetMapping("/{advrId}/citations")
    public ApiResponse<PolicyCitationResponse> citations(
            @PathVariable Long advrId,
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader) {
        roleGuard.requireAnyRole(roleHeader);
        return ApiResponse.ok(ragQueryService.getCitations(advrId));
    }
}
