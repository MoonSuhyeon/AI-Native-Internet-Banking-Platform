package com.bank.loan.advisory.service;

import com.bank.loan.advisory.domain.ReviewerDecisionSnapshot;
import com.bank.loan.advisory.dto.CohortStatsResponse;
import com.bank.loan.advisory.dto.PolicyCitationResponse;
import com.bank.loan.advisory.dto.ReviewerHistoryResponse;
import com.bank.loan.advisory.rag.PolicyCitationRetriever;
import com.bank.loan.advisory.rag.SimilarCaseRetriever;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import com.bank.loan.advisory.repository.ReviewerDecisionSnapshotRepository;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.repository.LoanReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdvisoryToolQueryServiceTest {

    PolicyCitationRetriever            citationRetriever   = mock(PolicyCitationRetriever.class);
    SimilarCaseRetriever               similarCaseRetriever = mock(SimilarCaseRetriever.class);
    ReviewAdvisoryReportRepository     reportRepo          = mock(ReviewAdvisoryReportRepository.class);
    LoanReviewRepository               loanReviewRepo      = mock(LoanReviewRepository.class);
    ReviewerDecisionSnapshotRepository snapshotRepo        = mock(ReviewerDecisionSnapshotRepository.class);

    AdvisoryToolQueryService service;

    @BeforeEach
    void setUp() {
        service = new AdvisoryToolQueryService(
                citationRetriever, similarCaseRetriever, reportRepo, loanReviewRepo, snapshotRepo);
    }

    @Test
    void queryCitations_PolicyCitationRetriever에_위임() {
        PolicyCitationResponse expected = new PolicyCitationResponse(null, 0, List.of());
        when(citationRetriever.retrieve(isNull(), anyString(), anyString(), anyInt(), isNull()))
                .thenReturn(expected);

        PolicyCitationResponse result = service.queryCitations("DSR 한도 예외");

        assertThat(result).isSameAs(expected);
        verify(citationRetriever).retrieve(isNull(), anyString(), anyString(), anyInt(), isNull());
    }

    @Test
    void queryReviewerHistory_승인거절_건수_집계() {
        Long reviewerId = 201L;
        LoanReview approved = stubReview(LoanReview.DECISION_APPROVED);
        LoanReview rejected = stubReview(LoanReview.DECISION_REJECTED);
        LoanReview approved2 = stubReview(LoanReview.DECISION_APPROVED);
        when(loanReviewRepo.findByReviewerIdAndReviewedAtGreaterThanEqualAndDeletedAtIsNull(
                anyLong(), any(OffsetDateTime.class)))
                .thenReturn(List.of(approved, rejected, approved2));

        ReviewerHistoryResponse result = service.queryReviewerHistory(reviewerId, 90);

        assertThat(result.reviewerId()).isEqualTo(reviewerId);
        assertThat(result.totalCount()).isEqualTo(3);
        assertThat(result.approvedCount()).isEqualTo(2);
        assertThat(result.rejectedCount()).isEqualTo(1);
        assertThat(result.approvalRate()).isCloseTo(2.0 / 3, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void queryReviewerHistory_데이터_없으면_0_반환() {
        when(loanReviewRepo.findByReviewerIdAndReviewedAtGreaterThanEqualAndDeletedAtIsNull(
                anyLong(), any())).thenReturn(List.of());

        ReviewerHistoryResponse result = service.queryReviewerHistory(999L, 30);

        assertThat(result.totalCount()).isEqualTo(0);
        assertThat(result.approvalRate()).isEqualTo(0.0);
    }

    @Test
    void queryCohortStats_최근_snapshotDate_항목만_집계() {
        ReviewerDecisionSnapshot s1 = stubSnapshot("20260501", 10, 7, 3, 7000, 3000);
        ReviewerDecisionSnapshot s2 = stubSnapshot("20260501", 20, 12, 8, 6000, 4000);
        ReviewerDecisionSnapshot old = stubSnapshot("20260401", 15, 10, 5, 6667, 3333);
        when(snapshotRepo.findByCohortDimensionCdAndCohortValueOrderBySnapshotDateDesc(
                anyString(), anyString()))
                .thenReturn(List.of(s1, s2, old));

        CohortStatsResponse result = service.queryCohortStats("EMPLOYMENT_TYPE", "SELF_EMPLOYED");

        assertThat(result.latestSnapshotDate()).isEqualTo("20260501");
        assertThat(result.reviewerCount()).isEqualTo(2);
        assertThat(result.totalReviews()).isEqualTo(30);
        assertThat(result.totalApproved()).isEqualTo(19);
        assertThat(result.totalRejected()).isEqualTo(11);
        assertThat(result.avgApproveRateBps()).isCloseTo(6500.0, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    void queryCohortStats_스냅샷_없으면_빈_응답() {
        when(snapshotRepo.findByCohortDimensionCdAndCohortValueOrderBySnapshotDateDesc(
                anyString(), anyString())).thenReturn(List.of());

        CohortStatsResponse result = service.queryCohortStats("LOAN_PURPOSE", "HOME_PURCHASE");

        assertThat(result.reviewerCount()).isEqualTo(0);
        assertThat(result.latestSnapshotDate()).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private LoanReview stubReview(String decisionCd) {
        LoanReview r = mock(LoanReview.class);
        when(r.getRevDecisionCd()).thenReturn(decisionCd);
        return r;
    }

    private ReviewerDecisionSnapshot stubSnapshot(String date, int total, int approve, int reject,
                                                   int approveRateBps, int rejectRateBps) {
        ReviewerDecisionSnapshot s = mock(ReviewerDecisionSnapshot.class);
        when(s.getSnapshotDate()).thenReturn(date);
        when(s.getTotalReviewCount()).thenReturn(total);
        when(s.getApproveCount()).thenReturn(approve);
        when(s.getRejectCount()).thenReturn(reject);
        when(s.getApproveRateBps()).thenReturn(approveRateBps);
        when(s.getRejectRateBps()).thenReturn(rejectRateBps);
        return s;
    }
}
