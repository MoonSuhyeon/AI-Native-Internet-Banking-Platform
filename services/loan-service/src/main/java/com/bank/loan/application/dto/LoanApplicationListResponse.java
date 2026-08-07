package com.bank.loan.application.dto;

import java.util.List;

/**
 * 고객 대출 신청 목록 응답.
 *
 * <p>Map 대신 record 로 두는 이유는 {@code LoanContractListResponse} 와 같다 —
 * OpenAPI 스펙에 모양을 남겨 프론트가 타입을 생성할 수 있게 한다. JSON 은 그대로다.
 */
public record LoanApplicationListResponse(
        List<LoanApplicationResponse> items,
        long totalCount
) {
    public static LoanApplicationListResponse of(List<LoanApplicationResponse> items) {
        return new LoanApplicationListResponse(items, items.size());
    }
}
