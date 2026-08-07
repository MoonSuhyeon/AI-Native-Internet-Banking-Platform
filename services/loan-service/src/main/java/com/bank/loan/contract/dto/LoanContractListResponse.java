package com.bank.loan.contract.dto;

import java.util.List;

/**
 * 고객 대출 계약 목록 응답.
 *
 * <p><b>왜 record 로 두는가.</b> 예전에는 {@code Map.of("items", ..., "totalCount", ...)} 를
 * 그대로 돌려줬다. JSON 은 같지만 OpenAPI 스펙에는 모양이 남지 않아
 * ({@code ApiResponseMapStringObject}) 프론트가 타입을 생성할 수 없었다.
 * 프론트는 그 자리를 손으로 적었고, 어긋나도 아무도 몰랐다.
 *
 * <p>키 이름과 JSON 구조는 이전과 같다 — 응답은 바뀌지 않는다.
 */
public record LoanContractListResponse(
        List<LoanContractResponse> items,
        long totalCount
) {
    public static LoanContractListResponse of(List<LoanContractResponse> items) {
        return new LoanContractListResponse(items, items.size());
    }
}
