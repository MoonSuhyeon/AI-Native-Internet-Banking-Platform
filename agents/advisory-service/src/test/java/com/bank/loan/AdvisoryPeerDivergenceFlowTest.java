package com.bank.loan;

import com.bank.loan.advisory.batch.AdvisoryBatchEvaluationService;
import com.bank.loan.advisory.batch.AdvisoryBatchEvaluationService.BatchEvaluationResult;
import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.creditevaluation.domain.CreditEvaluation;
import com.bank.loan.creditevaluation.repository.CreditEvaluationRepository;
import com.bank.loan.dsr.domain.DsrCalculation;
import com.bank.loan.dsr.repository.DsrCalculationRepository;
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
 * PEER_DECISION_DIVERGENCE 룰 BATCH 통합 — 유사 신청자 결정 70:30 분기 + 본 건 소수 결정.
 *
 * 시드 시나리오(테스트 연도 2050):
 *   - 같은 프로파일(creditScore=720, dsrRatioBps=3500) 의 본심사 10건이 90일 윈도우 안에 분산:
 *     7 APPROVED + 3 REJECTED (70:30)
 *   - 본 건은 평가일(2050-06-15) 의 같은 프로파일 1건 — REJECTED (=소수 결정)
 *
 * 배치 평가 후 본 건에 PEER_DECISION_DIVERGENCE WARN 리포트 발행 검증.
 */
class AdvisoryPeerDivergenceFlowTest extends AbstractLoanIntegrationTest {

    private static final String BASE_DATE = "20500615";
    private static final int    CREDIT_SCORE = 720;
    private static final int    DSR_BPS = 3500;
    private static final Long   TARGET_REVIEWER_ID = 99_301L;

    @Autowired LoanApplicationRepository applicationRepo;
    @Autowired CreditEvaluationRepository creditEvalRepo;
    @Autowired DsrCalculationRepository dsrRepo;
    @Autowired LoanReviewRepository reviewRepo;
    @Autowired ReviewAdvisoryReportRepository reportRepo;
    @Autowired AdvisoryBatchEvaluationService batchService;

    @Test
    void 유사_신청자_70_30_분기_본_건_소수_결정_시_WARN_리포트_발행() {
        OffsetDateTime baseDay = OffsetDateTime.of(2050, 6, 15, 10, 0, 0, 0, ZoneOffset.UTC);

        // 1) 매칭 그룹 10건 (윈도우 1~89일 전, 다른 reviewer/customer/applId, 동일 프로파일)
        for (int i = 0; i < 10; i++) {
            String decision = (i < 7) ? LoanReview.DECISION_APPROVED : LoanReview.DECISION_REJECTED;
            Long applId = saveApplicationWithProfile();
            saveReview(applId, 99_400L + i, decision, baseDay.minusDays(2L + i * 3));
        }

        // 2) 본 건 — 평가일에 REJECTED (소수 결정)
        Long targetApplId = saveApplicationWithProfile();
        Long targetRevId = saveReview(targetApplId, TARGET_REVIEWER_ID, LoanReview.DECISION_REJECTED, baseDay)
                .getRevId();

        // 3) 배치 평가
        BatchEvaluationResult result = batchService.runDailyBatch(BASE_DATE);
        assertThat(result.reportsPublished()).isGreaterThanOrEqualTo(1);

        // 4) 본 건의 본심사에 PEER_DECISION_DIVERGENCE 리포트 발행 검증
        List<ReviewAdvisoryReport> reports = reportRepo
                .findByRevIdAndDeletedAtIsNullOrderByGeneratedAtDesc(targetRevId);
        assertThat(reports).isNotEmpty();

        ReviewAdvisoryReport peerReport = reports.stream()
                .filter(r -> "REREVIEW_RECOMMEND".equals(r.getAdvisoryTypeCd()))
                .filter(r -> ReviewAdvisoryReport.SEVERITY_WARN.equals(r.getSeverityCd()))
                .findFirst()
                .orElseThrow(() ->
                        new AssertionError("PEER_DECISION_DIVERGENCE WARN 리포트가 발행되어야 함"));
        assertThat(peerReport.getAdvrTitle()).contains("유사 신청자 결정 분기");
        assertThat(peerReport.getTargetReviewerId()).isEqualTo(TARGET_REVIEWER_ID);
    }

    // ============================================================
    // helpers — 동일 프로파일의 신청·신용평가·DSR·본심사 세트 생성
    // ============================================================

    private Long saveApplicationWithProfile() {
        Long applId = applicationRepo.save(LoanApplication.builder()
                .applNo("ADVP_2050_" + UUID.randomUUID().toString().substring(0, 12))
                .customerId(2_050_000L + (long) (Math.random() * 999_999))
                .prodId(99_999L)
                .channelCd("TEST")
                .requestedAmount(10_000_000L)
                .requestedPeriodMo(24)
                .loanPurposeCd("PEER_TEST_PURPOSE_2050")
                .repaymentMethodCd("EQUAL")
                .applStatusCd(LoanApplication.STATUS_APPROVED)
                .appliedAt(OffsetDateTime.now())
                .build()).getApplId();

        creditEvalRepo.save(CreditEvaluation.builder()
                .applId(applId)
                .customerId(2_050_000L + (long) (Math.random() * 999_999))
                .cevalEngine("KCB")
                .cevalScore(CREDIT_SCORE)
                .cevalDecisionCd(CreditEvaluation.DECISION_APPROVE)
                .cevalStatusCd(CreditEvaluation.STATUS_COMPLETED)
                .evaluatedAt(OffsetDateTime.now())
                .build());

        dsrRepo.save(DsrCalculation.builder()
                .applId(applId)
                .customerId(2_050_000L + (long) (Math.random() * 999_999))
                .annualIncomeAmt(80_000_000L)
                .existingPrincipalTotal(0L)
                .existingAnnualRepayAmt(0L)
                .newAnnualRepayAmt(10_000_000L)
                .totalAnnualRepayAmt(10_000_000L)
                .dsrRatioBps(DSR_BPS)
                .dsrLimitBps(4000)
                .dsrStatusCd(DsrCalculation.STATUS_PASS)
                .calculatedAt(OffsetDateTime.now())
                .build());

        return applId;
    }

    private LoanReview saveReview(Long applId, Long reviewerId, String decisionCd, OffsetDateTime reviewedAt) {
        return reviewRepo.save(LoanReview.builder()
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
