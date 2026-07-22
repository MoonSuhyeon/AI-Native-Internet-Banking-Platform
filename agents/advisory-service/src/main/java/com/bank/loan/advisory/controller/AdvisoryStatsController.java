package com.bank.loan.advisory.controller;

import com.bank.common.web.ApiResponse;
import com.bank.loan.advisory.dto.ReviewerAckStatsResponse;
import com.bank.loan.advisory.service.AdvisoryRoleGuard;
import com.bank.loan.advisory.service.AdvisoryStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "어드바이저리 통계", description = "Advisory - 운영·감사 통계 (auditor/admin)")
@RestController
@RequestMapping("/api/advisory/stats")
@RequiredArgsConstructor
public class AdvisoryStatsController {

    private final AdvisoryStatsService statsService;
    private final AdvisoryRoleGuard roleGuard;

    @Operation(summary = "심사관별 ack 통계",
            description = "리포트 총수 + 미해결 수 + ack 응답코드별 카운트 + 룰별 트리거 빈도. auditor/admin 권한.")
    @GetMapping("/reviewers/{reviewerId}")
    public ApiResponse<ReviewerAckStatsResponse> reviewerStats(
            @PathVariable Long reviewerId,
            @RequestHeader(value = "X-Actor-Role", required = false) String roleHeader) {
        roleGuard.requireAuditorOrAdmin(roleHeader);
        return ApiResponse.ok(statsService.statsForReviewer(reviewerId));
    }
}
