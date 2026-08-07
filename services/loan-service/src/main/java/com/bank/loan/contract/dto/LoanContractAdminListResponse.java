package com.bank.loan.contract.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 운영자 계약 목록 응답(페이지).
 *
 * <p>Map 대신 record 로 두는 이유는 {@code LoanContractListResponse} 와 같다.
 * 키 이름·구조는 이전과 같다.
 */
public record LoanContractAdminListResponse(
        List<LoanContractResponse> items,
        long totalCount,
        int totalPages,
        int currentPage
) {
    public static LoanContractAdminListResponse of(Page<LoanContractResponse> page) {
        return new LoanContractAdminListResponse(
                page.getContent(), page.getTotalElements(), page.getTotalPages(), page.getNumber());
    }
}
