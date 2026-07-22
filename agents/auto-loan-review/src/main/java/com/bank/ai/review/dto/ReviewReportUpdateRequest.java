package com.bank.ai.review.dto;

import com.bank.ai.llm.report.ReviewReport;

/**
 * 리포트 업데이트 요청 DTO — loan-service PATCH /api/loan-applications/reviews/{revId}/report.
 *
 * @param status          "DONE" 또는 "FAILED"
 * @param report          트랙별 심사 리포트 (DONE 시 non-null)
 * @param agentOpinionJson 에이전트 의견 JSON 문자열 (A6 신규, null 허용)
 */
public record ReviewReportUpdateRequest(
        String status,
        ReviewReport report,
        String agentOpinionJson
) {
}
