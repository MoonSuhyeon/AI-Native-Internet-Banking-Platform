package com.bank.loan.advisory.service;

/**
 * 리포트 목록 조회 필터. null 필드는 미적용.
 * 단, role=REVIEWER 인 경우 {@code targetReviewerId} 는 actorId 로 강제된다.
 */
public record AdvisoryReportListFilter(
        Long targetReviewerId,
        Long revId,
        String advisoryTypeCd,
        String severityCd,
        String advrStatusCd
) {}
