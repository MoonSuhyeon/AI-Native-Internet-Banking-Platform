package com.bank.loan.advisory.service;

/**
 * 어드바이저리 조회 권한 역할.
 *   REVIEWER : 본인 대상 리포트만 조회 가능 (자신이 targetReviewerId 인 건)
 *   AUDITOR  : 전체 리포트 조회 (감사 목적), 변경 불가
 *   ADMIN    : 전체 조회 + 룰 변경 가능
 *
 * Phase 4-5 에서 Spring Security 가드와 연동. 그 전까지 ReportController 가
 * `X-Actor-Role` 헤더로 받음.
 */
public enum AdvisoryViewerRole {
    REVIEWER, AUDITOR, ADMIN;

    public static AdvisoryViewerRole parse(String header) {
        if (header == null || header.isBlank()) return REVIEWER;
        return switch (header.trim().toUpperCase()) {
            case "REVIEWER" -> REVIEWER;
            case "AUDITOR"  -> AUDITOR;
            case "ADMIN"    -> ADMIN;
            default -> throw new IllegalArgumentException("invalid X-Actor-Role: " + header);
        };
    }

    public boolean canSeeAll() {
        return this == AUDITOR || this == ADMIN;
    }
}
