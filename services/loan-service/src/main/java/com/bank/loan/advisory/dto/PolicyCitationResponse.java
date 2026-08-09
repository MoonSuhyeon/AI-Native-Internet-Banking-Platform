package com.bank.loan.advisory.dto;

import java.util.List;

/**
 * 정책 인용 검색 응답 (plan §11.5 — GET /advisory/reports/{advr_id}/citations).
 * advrPayload.citations 의 풀 페이로드를 반환한다.
 * CRITICAL 룰 발화 시 자동 적재(6-7), 심사관 요청 시 이 API 로 조회.
 */
public record PolicyCitationResponse(
        Long   advrId,
        int    totalCount,
        List<CitationItem> citations
) {
    public record CitationItem(
            Long   chunkId,
            Long   docId,
            String docCd,
            String docTitle,
            String sectionPath,
            String chunkText,
            double score
    ) {}
}
