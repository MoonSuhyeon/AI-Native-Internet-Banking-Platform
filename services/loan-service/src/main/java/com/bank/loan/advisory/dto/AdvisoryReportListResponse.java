package com.bank.loan.advisory.dto;

import java.util.List;

/** 리포트 목록 응답 — 총개수 + 항목. */
public record AdvisoryReportListResponse(int totalCount, List<AdvisoryReportSummaryResponse> items) {
    public static AdvisoryReportListResponse of(List<AdvisoryReportSummaryResponse> items) {
        return new AdvisoryReportListResponse(items.size(), items);
    }
}
