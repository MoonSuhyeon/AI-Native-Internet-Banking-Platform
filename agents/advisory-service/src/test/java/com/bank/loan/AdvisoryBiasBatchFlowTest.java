package com.bank.loan;

import com.bank.loan.advisory.batch.AdvisoryBatchEvaluationService;
import com.bank.loan.advisory.batch.AdvisoryBatchEvaluationService.BatchEvaluationResult;
import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.repository.LoanReviewRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 어드바이저리 BATCH 평가 — 편향 감지(BIAS_REJECT/APPROVAL_RATE_DEVIATION).
 *
 * 시드 시나리오(테스트 연도 2040):
 *   - 5명 reviewer × 각 30건 본심사 = 150건
 *   - 모두 동일 loan_purpose_cd → 동일 코호트 (LOAN_PURPOSE / "ADV_TEST_PURPOSE")
 *   - reviewerA(99_201): 28거절/2승인 (거절율 93%) — peer 평균(31~40%) 대비 +이상치
 *   - reviewerB~E    : 9/10/11/12 거절 (30~40%)
 *   - peer 표준편차 작음 → reviewerA 의 deviation_sigma 가 +2σ 초과 (reject) 동시에 -2σ 미만 (approve)
 *
 * 검증: 배치 평가 후 reviewerA 의 30건 본심사에 BIAS_DETECTION WARN 리포트 발행, 다른 reviewer 에는 없음.
 */
class AdvisoryBiasBatchFlowTest extends AbstractLoanIntegrationTest {

    private static final String BASE_DATE = "20400615";
    private static final String COHORT_PURPOSE = "ADV_TEST_PURPOSE_2040";
    private static final Long[] REVIEWER_IDS = { 99_201L, 99_202L, 99_203L, 99_204L, 99_205L };
    private static final int[] REJECT_COUNTS = { 28, 9, 10, 11, 12 };
    private static final int REVIEWS_PER_REVIEWER = 30;

    @Autowired LoanApplicationRepository applicationRepo;
    @Autowired LoanReviewRepository reviewRepo;
    @Autowired ReviewAdvisoryReportRepository reportRepo;
    @Autowired AdvisoryBatchEvaluationService batchService;

    @Test
    void 편향_시드_배치_평가_시_거절율_편차_WARN_리포트_발행() {
        OffsetDateTime base = OffsetDateTime.of(2040, 6, 15, 10, 0, 0, 0, ZoneOffset.UTC);

        for (int r = 0; r < REVIEWER_IDS.length; r++) {
            for (int i = 0; i < REVIEWS_PER_REVIEWER; i++) {
                Long applId = saveApplication();
                boolean reject = i < REJECT_COUNTS[r];
                saveReview(applId, REVIEWER_IDS[r],
                        reject ? LoanReview.DECISION_REJECTED : LoanReview.DECISION_APPROVED,
                        base.plusMinutes(r * 30L + i));
            }
        }

        BatchEvaluationResult result = batchService.runDailyBatch(BASE_DATE);

        // snapshot — 코호트 LOAN_PURPOSE 1종 × reviewer 5명 = 5 row 적재
        assertThat(result.snapshot().reviewCount()).isEqualTo(REVIEWER_IDS.length * REVIEWS_PER_REVIEWER);
        assertThat(result.snapshot().inserted()).isEqualTo(REVIEWER_IDS.length);

        // reportsPublished — A 의 30건 본심사에 리포트가 적재되어야 함
        assertThat(result.reportsPublished()).isGreaterThanOrEqualTo(REVIEWS_PER_REVIEWER);

        // reviewerA(99_201) 의 30 본심사에 BIAS_DETECTION WARN 리포트
        List<ReviewAdvisoryReport> aReports = reportRepo
                .findByTargetReviewerIdAndDeletedAtIsNullOrderByGeneratedAtDesc(REVIEWER_IDS[0]);
        assertThat(aReports).isNotEmpty();
        assertThat(aReports).allMatch(rp -> "BIAS_DETECTION".equals(rp.getAdvisoryTypeCd()));
        assertThat(aReports).allMatch(rp -> ReviewAdvisoryReport.SEVERITY_WARN.equals(rp.getSeverityCd()));

        // 다른 reviewer 들에는 BIAS_DETECTION 리포트 없음
        for (int r = 1; r < REVIEWER_IDS.length; r++) {
            List<ReviewAdvisoryReport> others = reportRepo
                    .findByTargetReviewerIdAndDeletedAtIsNullOrderByGeneratedAtDesc(REVIEWER_IDS[r]);
            assertThat(others)
                    .as("reviewerId=%d 에는 BIAS_DETECTION 리포트가 없어야 함", REVIEWER_IDS[r])
                    .noneMatch(rp -> "BIAS_DETECTION".equals(rp.getAdvisoryTypeCd()));
        }
    }

    private Long saveApplication() {
        return applicationRepo.save(LoanApplication.builder()
                .applNo("ADVB_2040_" + UUID.randomUUID().toString().substring(0, 12))
                .customerId(2_040_000L + (long) (Math.random() * 99_999))
                .prodId(99_999L)
                .channelCd("TEST")
                .requestedAmount(10_000_000L)
                .requestedPeriodMo(24)
                .loanPurposeCd(COHORT_PURPOSE)
                .repaymentMethodCd("EQUAL")
                .applStatusCd(LoanApplication.STATUS_REJECTED)
                .appliedAt(OffsetDateTime.now())
                .build()).getApplId();
    }

    private void saveReview(Long applId, Long reviewerId, String decisionCd, OffsetDateTime reviewedAt) {
        reviewRepo.save(LoanReview.builder()
                .applId(applId)
                .revTypeCd(LoanReview.TYPE_MANUAL)
                .revStatusCd(LoanReview.STATUS_COMPLETED)
                .revDecisionCd(decisionCd)
                .approvedAmount(LoanReview.DECISION_APPROVED.equals(decisionCd) ? 10_000_000L : null)
                .approvedRateBps(LoanReview.DECISION_APPROVED.equals(decisionCd) ? 500 : null)
                .approvedPeriodMo(LoanReview.DECISION_APPROVED.equals(decisionCd) ? 24 : null)
                .reviewerId(reviewerId)
                .reviewedAt(reviewedAt)
                .approvedAt(LoanReview.DECISION_APPROVED.equals(decisionCd) ? reviewedAt : null)
                .build());
    }
}
