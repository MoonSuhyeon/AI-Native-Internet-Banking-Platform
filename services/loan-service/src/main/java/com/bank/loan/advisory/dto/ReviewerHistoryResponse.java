package com.bank.loan.advisory.dto;

public record ReviewerHistoryResponse(
        Long   reviewerId,
        int    days,
        int    totalCount,
        int    approvedCount,
        int    rejectedCount,
        double approvalRate
) {}
