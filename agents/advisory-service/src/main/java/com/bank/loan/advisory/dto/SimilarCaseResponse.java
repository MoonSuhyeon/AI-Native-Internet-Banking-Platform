package com.bank.loan.advisory.dto;

import java.util.List;

/**
 * 유사 과거 사례 검색 응답 (plan §11.5 — GET /advisory/reports/{advr_id}/similar-cases).
 * summaryText 는 PII 마스킹 후 저장된 익명화 텍스트.
 */
public record SimilarCaseResponse(
        Long   advrId,
        int    totalCount,
        List<CaseItem> cases
) {
    public record CaseItem(
            Long   caseIdxId,
            Long   revId,
            String decisionCd,
            String overturnYn,
            Integer creditScore,
            Integer dsrRatioBps,
            Integer ltvRatioBps,
            String  cohortEmploymentTypeCd,
            String  cohortLoanPurposeCd,
            String  summaryText,
            double  score
    ) {}
}
