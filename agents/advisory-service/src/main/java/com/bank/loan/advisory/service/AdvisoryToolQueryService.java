package com.bank.loan.advisory.service;

import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.domain.ReviewerDecisionSnapshot;
import com.bank.loan.advisory.dto.CohortStatsResponse;
import com.bank.loan.advisory.dto.PolicyCitationResponse;
import com.bank.loan.advisory.dto.ReviewerHistoryResponse;
import com.bank.loan.advisory.dto.SimilarCaseResponse;
import com.bank.loan.advisory.rag.PolicyCitationRetriever;
import com.bank.loan.advisory.rag.SimilarCaseRetriever;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import com.bank.loan.advisory.repository.ReviewerDecisionSnapshotRepository;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.repository.LoanReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdvisoryToolQueryService {

    private static final int DEFAULT_TOP_K = 3;

    private final PolicyCitationRetriever            citationRetriever;
    private final SimilarCaseRetriever               similarCaseRetriever;
    private final ReviewAdvisoryReportRepository     reportRepo;
    private final LoanReviewRepository               loanReviewRepo;
    private final ReviewerDecisionSnapshotRepository snapshotRepo;

    @Transactional(readOnly = true)
    public PolicyCitationResponse queryCitations(String query) {
        return citationRetriever.retrieve(null, "TOOL_QUERY", query, DEFAULT_TOP_K, null);
    }

    @Transactional
    public SimilarCaseResponse querySimilarCasesByRevId(Long revId, int topK) {
        List<ReviewAdvisoryReport> reports =
                reportRepo.findByRevIdAndDeletedAtIsNullOrderByGeneratedAtDesc(revId);
        if (reports.isEmpty()) {
            return new SimilarCaseResponse(null, 0, List.of());
        }
        Long advrId = reports.get(0).getAdvrId();
        return similarCaseRetriever.retrieve(advrId, topK, null);
    }

    @Transactional(readOnly = true)
    public ReviewerHistoryResponse queryReviewerHistory(Long reviewerId, int days) {
        OffsetDateTime since = OffsetDateTime.now().minusDays(days);
        List<LoanReview> reviews = loanReviewRepo
                .findByReviewerIdAndReviewedAtGreaterThanEqualAndDeletedAtIsNull(reviewerId, since);

        int total    = reviews.size();
        int approved = (int) reviews.stream()
                .filter(r -> LoanReview.DECISION_APPROVED.equals(r.getRevDecisionCd()))
                .count();
        int rejected = (int) reviews.stream()
                .filter(r -> LoanReview.DECISION_REJECTED.equals(r.getRevDecisionCd()))
                .count();
        double approvalRate = total == 0 ? 0.0 : (double) approved / total;

        return new ReviewerHistoryResponse(reviewerId, days, total, approved, rejected, approvalRate);
    }

    @Transactional(readOnly = true)
    public CohortStatsResponse queryCohortStats(String dimension, String value) {
        List<ReviewerDecisionSnapshot> snapshots = snapshotRepo
                .findByCohortDimensionCdAndCohortValueOrderBySnapshotDateDesc(dimension, value);

        if (snapshots.isEmpty()) {
            return new CohortStatsResponse(dimension, value, null, 0, 0, 0, 0, 0.0, 0.0);
        }

        // 가장 최근 snapshotDate의 항목만 집계
        String latestDate = snapshots.get(0).getSnapshotDate();
        List<ReviewerDecisionSnapshot> latest = snapshots.stream()
                .filter(s -> latestDate.equals(s.getSnapshotDate()))
                .toList();

        int reviewerCount  = latest.size();
        int totalReviews   = latest.stream().mapToInt(ReviewerDecisionSnapshot::getTotalReviewCount).sum();
        int totalApproved  = latest.stream().mapToInt(ReviewerDecisionSnapshot::getApproveCount).sum();
        int totalRejected  = latest.stream().mapToInt(ReviewerDecisionSnapshot::getRejectCount).sum();
        double avgApprove  = latest.stream().mapToInt(ReviewerDecisionSnapshot::getApproveRateBps).average().orElse(0.0);
        double avgReject   = latest.stream().mapToInt(ReviewerDecisionSnapshot::getRejectRateBps).average().orElse(0.0);

        return new CohortStatsResponse(dimension, value, latestDate,
                reviewerCount, totalReviews, totalApproved, totalRejected, avgApprove, avgReject);
    }
}
