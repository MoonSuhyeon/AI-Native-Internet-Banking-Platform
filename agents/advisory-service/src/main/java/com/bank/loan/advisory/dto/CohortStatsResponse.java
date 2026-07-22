package com.bank.loan.advisory.dto;

public record CohortStatsResponse(
        String dimension,
        String value,
        String latestSnapshotDate,
        int    reviewerCount,
        int    totalReviews,
        int    totalApproved,
        int    totalRejected,
        double avgApproveRateBps,
        double avgRejectRateBps
) {}
