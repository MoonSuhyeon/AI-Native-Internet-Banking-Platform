package com.bank.loan;

import com.bank.loan.advisory.domain.ReviewAdvisoryAck;
import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.domain.ReviewAdvisoryRule;
import com.bank.loan.advisory.engine.AdvisoryEvaluator;
import com.bank.loan.advisory.engine.RuleContext;
import com.bank.loan.advisory.engine.rules.DsrThresholdOverrideRule;
import com.bank.loan.advisory.engine.rules.LtvThresholdOverrideRule;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import com.bank.loan.advisory.repository.ReviewAdvisoryRuleRepository;
import com.bank.loan.advisory.service.AdvisoryAckService;
import com.bank.loan.dsr.domain.DsrCalculation;
import com.bank.loan.dsr.repository.DsrCalculationRepository;
import com.bank.loan.ltv.domain.LtvCalculation;
import com.bank.loan.ltv.repository.LtvCalculationRepository;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.repository.LoanReviewRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 어드바이저리 룰/게이트 단위 검증.
 * 룰 입력(LoanReview/DSR/LTV row) 만 직접 박고 룰을 호출해 *조건 분기* 만 검증한다.
 * 본심사·약정의 사전조건은 통과 여부와 무관 (그쪽은 2-8 통합 테스트에서 풀 시나리오로 검증).
 */
class AdvisoryRuleAndGateUnitTest extends AbstractLoanIntegrationTest {

    @Autowired DsrThresholdOverrideRule dsrRule;
    @Autowired LtvThresholdOverrideRule ltvRule;
    @Autowired AdvisoryEvaluator evaluator;
    @Autowired AdvisoryAckService ackService;

    @Autowired LoanReviewRepository reviewRepo;
    @Autowired DsrCalculationRepository dsrRepo;
    @Autowired LtvCalculationRepository ltvRepo;
    @Autowired ReviewAdvisoryRuleRepository ruleRepo;
    @Autowired ReviewAdvisoryReportRepository reportRepo;

    // ============================================================
    // DSR 룰
    // ============================================================

    @Test
    void DsrRule_DSR_FAIL_본심사_APPROVED_시_CRITICAL_발화() {
        Long applId = randomId();
        Long revId = saveReview(applId, LoanReview.DECISION_APPROVED).getRevId();
        saveDsr(applId, DsrCalculation.STATUS_FAIL, 4500, 4000);

        var results = dsrRule.evaluate(RuleContext.sync(revId));

        assertThat(results).hasSize(1);
        var r = results.get(0);
        assertThat(r.severityCd()).isEqualTo(ReviewAdvisoryReport.SEVERITY_CRITICAL);
        assertThat(r.advisoryTypeCd()).isEqualTo("REREVIEW_RECOMMEND");
        assertThat(r.signals()).hasSize(1);
        assertThat(r.signals().get(0).signalKindCd()).isEqualTo("DSR_OVERRIDE");
    }

    @Test
    void DsrRule_DSR_PASS_빈_결과() {
        Long applId = randomId();
        Long revId = saveReview(applId, LoanReview.DECISION_APPROVED).getRevId();
        saveDsr(applId, DsrCalculation.STATUS_PASS, 3500, 4000);

        assertThat(dsrRule.evaluate(RuleContext.sync(revId))).isEmpty();
    }

    @Test
    void DsrRule_본심사_REJECTED_빈_결과() {
        Long applId = randomId();
        Long revId = saveReview(applId, LoanReview.DECISION_REJECTED).getRevId();
        saveDsr(applId, DsrCalculation.STATUS_FAIL, 4500, 4000);

        assertThat(dsrRule.evaluate(RuleContext.sync(revId))).isEmpty();
    }

    @Test
    void DsrRule_DSR_미존재_빈_결과() {
        Long revId = saveReview(randomId(), LoanReview.DECISION_APPROVED).getRevId();
        assertThat(dsrRule.evaluate(RuleContext.sync(revId))).isEmpty();
    }

    // ============================================================
    // LTV 룰
    // ============================================================

    @Test
    void LtvRule_LTV_FAIL_본심사_APPROVED_시_CRITICAL_발화() {
        Long applId = randomId();
        Long revId = saveReview(applId, LoanReview.DECISION_APPROVED).getRevId();
        saveLtv(applId, randomId(), LtvCalculation.STATUS_FAIL, 8500, 7000);

        var results = ltvRule.evaluate(RuleContext.sync(revId));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).severityCd()).isEqualTo(ReviewAdvisoryReport.SEVERITY_CRITICAL);
        assertThat(results.get(0).signals().get(0).signalKindCd()).isEqualTo("LTV_OVERRIDE");
    }

    @Test
    void LtvRule_LTV_PASS_빈_결과() {
        Long applId = randomId();
        Long revId = saveReview(applId, LoanReview.DECISION_APPROVED).getRevId();
        saveLtv(applId, randomId(), LtvCalculation.STATUS_PASS, 6500, 7000);

        assertThat(ltvRule.evaluate(RuleContext.sync(revId))).isEmpty();
    }

    // ============================================================
    // AdvisoryEvaluator — 룰 마스터 활성/비활성 분기
    // ============================================================

    @Test
    void Evaluator_DSR_FAIL_시나리오_리포트_시그널_적재() {
        Long applId = randomId();
        Long revId = saveReview(applId, LoanReview.DECISION_APPROVED).getRevId();
        saveDsr(applId, DsrCalculation.STATUS_FAIL, 4600, 4000);

        List<Long> advrIds = evaluator.evaluate(RuleContext.sync(revId));

        assertThat(advrIds).isNotEmpty();
        // DSR 만족 케이스에 대해 최소 1건 리포트 발행
        assertThat(reportRepo.findUnresolvedCriticalByRevId(revId)).isNotEmpty();
    }

    @Test
    void Evaluator_룰_마스터_비활성_시_no_op() {
        // DSR 룰 마스터를 비활성화 — detached entity 의 version 동기화를 위해 반환값 재할당
        ReviewAdvisoryRule master = ruleRepo
                .findByRuleCdAndDeletedAtIsNull(DsrThresholdOverrideRule.RULE_CD)
                .orElseThrow();
        master.deactivate();
        master = ruleRepo.saveAndFlush(master);
        try {
            Long applId = randomId();
            Long revId = saveReview(applId, LoanReview.DECISION_APPROVED).getRevId();
            saveDsr(applId, DsrCalculation.STATUS_FAIL, 4700, 4000);

            List<Long> advrIds = evaluator.evaluate(RuleContext.sync(revId));

            // DSR 룰은 비활성이라 skip, LTV 룰은 LTV row 없어 빈 결과
            assertThat(advrIds).isEmpty();
            assertThat(reportRepo.findUnresolvedCriticalByRevId(revId)).isEmpty();
        } finally {
            master.activate();
            ruleRepo.saveAndFlush(master);
        }
    }

    // ============================================================
    // AdvisoryAckService + findUnresolvedCriticalByRevId — ack 게이트 해제 검증
    // ============================================================

    @Test
    void Ack_등록_후_리포트_ACKED_전이_및_미해결_CRITICAL_빈_결과() {
        Long applId = randomId();
        Long revId = saveReview(applId, LoanReview.DECISION_APPROVED).getRevId();
        saveDsr(applId, DsrCalculation.STATUS_FAIL, 4400, 4000);
        evaluator.evaluate(RuleContext.sync(revId));

        List<ReviewAdvisoryReport> blockers = reportRepo.findUnresolvedCriticalByRevId(revId);
        assertThat(blockers).hasSize(1);

        Long advrId = blockers.get(0).getAdvrId();
        ReviewAdvisoryAck ack = ackService.acknowledge(advrId, AdvisoryAckService.AdvisoryAckCommand.builder()
                .ackResponseCd(ReviewAdvisoryAck.RESPONSE_MAINTAIN)
                .decisionChangeYn("N")
                .ackReasonCd("REVIEWER_JUDGMENT")
                .ackRemark("정책 예외 검토 후 유지")
                .beforeDecisionCd(LoanReview.DECISION_APPROVED)
                .afterDecisionCd(LoanReview.DECISION_APPROVED)
                .build());

        assertThat(ack.getAdvkId()).isNotNull();
        ReviewAdvisoryReport reloaded = reportRepo.findById(advrId).orElseThrow();
        assertThat(reloaded.getAdvrStatusCd()).isEqualTo(ReviewAdvisoryReport.STATUS_ACKED);

        // 게이트 해제
        assertThat(reportRepo.findUnresolvedCriticalByRevId(revId)).isEmpty();
    }

    // ============================================================
    // helpers
    // ============================================================

    private static Long randomId() {
        return ThreadLocalRandom.current().nextLong(8_000_000L, 8_999_999L);
    }

    private LoanReview saveReview(Long applId, String decisionCd) {
        return reviewRepo.save(LoanReview.builder()
                .applId(applId)
                .revTypeCd(LoanReview.TYPE_MANUAL)
                .revStatusCd(LoanReview.STATUS_COMPLETED)
                .revDecisionCd(decisionCd)
                .approvedAmount(LoanReview.DECISION_APPROVED.equals(decisionCd) ? 30_000_000L : null)
                .approvedRateBps(LoanReview.DECISION_APPROVED.equals(decisionCd) ? 500 : null)
                .approvedPeriodMo(LoanReview.DECISION_APPROVED.equals(decisionCd) ? 24 : null)
                .reviewerId(randomId())
                .reviewedAt(OffsetDateTime.now())
                .approvedAt(LoanReview.DECISION_APPROVED.equals(decisionCd) ? OffsetDateTime.now() : null)
                .build());
    }

    private DsrCalculation saveDsr(Long applId, String statusCd, int ratioBps, int limitBps) {
        return dsrRepo.save(DsrCalculation.builder()
                .applId(applId)
                .customerId(randomId())
                .annualIncomeAmt(60_000_000L)
                .existingPrincipalTotal(0L)
                .existingAnnualRepayAmt(0L)
                .newAnnualRepayAmt(15_000_000L)
                .totalAnnualRepayAmt(15_000_000L)
                .dsrRatioBps(ratioBps)
                .dsrLimitBps(limitBps)
                .dsrStatusCd(statusCd)
                .calculatedAt(OffsetDateTime.now())
                .build());
    }

    private LtvCalculation saveLtv(Long applId, Long colId, String statusCd, int ratioBps, int limitBps) {
        return ltvRepo.save(LtvCalculation.builder()
                .applId(applId)
                .colId(colId)
                .appliedColValue(300_000_000L)
                .seniorLienAmount(0L)
                .requestedAmount(200_000_000L)
                .ltvRatioBps(ratioBps)
                .ltvLimitBps(limitBps)
                .maxLoanAmount(210_000_000L)
                .ltvStatusCd(statusCd)
                .calculatedAt(OffsetDateTime.now())
                .build());
    }
}
