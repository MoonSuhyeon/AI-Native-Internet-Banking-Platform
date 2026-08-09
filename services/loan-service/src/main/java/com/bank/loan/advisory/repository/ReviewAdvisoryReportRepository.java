package com.bank.loan.advisory.repository;

import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ReviewAdvisoryReportRepository extends JpaRepository<ReviewAdvisoryReport, Long> {

    List<ReviewAdvisoryReport> findByRevIdAndDeletedAtIsNullOrderByGeneratedAtDesc(Long revId);

    List<ReviewAdvisoryReport> findByTargetReviewerIdAndDeletedAtIsNullOrderByGeneratedAtDesc(Long targetReviewerId);

    List<ReviewAdvisoryReport> findByRevIdAndSeverityCdAndAdvrStatusCdInAndDeletedAtIsNull(
            Long revId, String severityCd, Collection<String> advrStatusCds);

    /** ack 게이트용 — 동일 심사의 미해결(OPEN/VIEWED) CRITICAL 리포트 목록. */
    default List<ReviewAdvisoryReport> findUnresolvedCriticalByRevId(Long revId) {
        return findByRevIdAndSeverityCdAndAdvrStatusCdInAndDeletedAtIsNull(
                revId,
                ReviewAdvisoryReport.SEVERITY_CRITICAL,
                List.of(ReviewAdvisoryReport.STATUS_OPEN, ReviewAdvisoryReport.STATUS_VIEWED));
    }

    long countBySeverityCdAndAdvrStatusCdInAndDeletedAtIsNull(
            String severityCd, Collection<String> advrStatusCds);

    /** Prometheus open_reports gauge 용 — 미해결(OPEN/VIEWED) 리포트 수. */
    default long countOpenBySeverity(String severityCd) {
        return countBySeverityCdAndAdvrStatusCdInAndDeletedAtIsNull(
                severityCd,
                List.of(ReviewAdvisoryReport.STATUS_OPEN, ReviewAdvisoryReport.STATUS_VIEWED));
    }

    List<ReviewAdvisoryReport> findByRevIdAndAdvrStatusCdNotAndDeletedAtIsNull(Long revId, String advrStatusCd);

    /** 결정 변경 종결 대상 — RESOLVED 가 아닌 모든 활성 리포트. */
    default List<ReviewAdvisoryReport> findActiveByRevId(Long revId) {
        return findByRevIdAndAdvrStatusCdNotAndDeletedAtIsNull(revId, ReviewAdvisoryReport.STATUS_RESOLVED);
    }

    List<ReviewAdvisoryReport> findByAdvrStatusCdAndDeletedAtIsNullOrderByQuarantinedAtDesc(String advrStatusCd);

    /** AI 격리 신호 목록 — quarantined_at 최신순. */
    default List<ReviewAdvisoryReport> findAllQuarantined() {
        return findByAdvrStatusCdAndDeletedAtIsNullOrderByQuarantinedAtDesc(
                ReviewAdvisoryReport.STATUS_QUARANTINE);
    }
}
