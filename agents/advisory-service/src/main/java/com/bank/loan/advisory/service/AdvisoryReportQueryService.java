package com.bank.loan.advisory.service;

import com.bank.common.web.BusinessException;
import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.dto.AdvisoryReportDetailResponse;
import com.bank.loan.advisory.dto.AdvisoryReportSummaryResponse;
import com.bank.loan.advisory.repository.ReviewAdvisoryAckRepository;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import com.bank.loan.advisory.repository.ReviewAdvisorySignalRepository;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 어드바이저리 리포트 조회·조회마킹 서비스.
 *
 * 권한 규칙:
 *   REVIEWER : 자신이 target_reviewer_id 인 리포트만 노출. 타인 리포트 GET 시 404 (LOAN_190).
 *   AUDITOR/ADMIN : 전체 노출, 필터 자유 적용.
 *
 * Phase 4-5 에서 Spring Security 가드가 추가되면 컨트롤러가 SecurityContext 에서 role 을 주입.
 */
@Service
@RequiredArgsConstructor
public class AdvisoryReportQueryService {

    private final ReviewAdvisoryReportRepository reportRepo;
    private final ReviewAdvisorySignalRepository signalRepo;
    private final ReviewAdvisoryAckRepository ackRepo;

    @Transactional(readOnly = true)
    public List<AdvisoryReportSummaryResponse> findForViewer(Long actorId,
                                                             AdvisoryViewerRole role,
                                                             AdvisoryReportListFilter filter) {
        List<ReviewAdvisoryReport> base = (role == AdvisoryViewerRole.REVIEWER)
                ? reportRepo.findByTargetReviewerIdAndDeletedAtIsNullOrderByGeneratedAtDesc(actorId)
                : reportRepo.findAll().stream().filter(r -> r.getDeletedAt() == null).toList();

        return base.stream()
                .filter(r -> filter.targetReviewerId() == null
                        || filter.targetReviewerId().equals(r.getTargetReviewerId()))
                .filter(r -> filter.revId() == null || filter.revId().equals(r.getRevId()))
                .filter(r -> filter.advisoryTypeCd() == null
                        || filter.advisoryTypeCd().equals(r.getAdvisoryTypeCd()))
                .filter(r -> filter.severityCd() == null || filter.severityCd().equals(r.getSeverityCd()))
                .filter(r -> filter.advrStatusCd() == null
                        || filter.advrStatusCd().equals(r.getAdvrStatusCd()))
                .sorted((a, b) -> b.getGeneratedAt().compareTo(a.getGeneratedAt()))
                .map(AdvisoryReportSummaryResponse::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdvisoryReportDetailResponse getDetail(Long advrId, Long actorId, AdvisoryViewerRole role) {
        ReviewAdvisoryReport report = loadAccessible(advrId, actorId, role);
        return AdvisoryReportDetailResponse.of(
                report,
                signalRepo.findByAdvrIdOrderByObservedAtAsc(report.getAdvrId()),
                ackRepo.findByAdvrIdOrderByAckedAtAsc(report.getAdvrId()));
    }

    @Transactional
    public AdvisoryReportSummaryResponse markViewed(Long advrId, Long actorId, AdvisoryViewerRole role) {
        ReviewAdvisoryReport report = loadAccessible(advrId, actorId, role);
        report.markViewed(OffsetDateTime.now());
        return AdvisoryReportSummaryResponse.of(report);
    }

    private ReviewAdvisoryReport loadAccessible(Long advrId, Long actorId, AdvisoryViewerRole role) {
        ReviewAdvisoryReport report = reportRepo.findById(advrId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_190));
        // reviewer 가 본인 대상 아닌 리포트에 접근 시 정보 유출 방지 — 존재 자체를 숨기기 위해 404
        if (role == AdvisoryViewerRole.REVIEWER
                && !Objects.equals(report.getTargetReviewerId(), actorId)) {
            throw new BusinessException(LoanErrorCode.LOAN_190);
        }
        return report;
    }
}
