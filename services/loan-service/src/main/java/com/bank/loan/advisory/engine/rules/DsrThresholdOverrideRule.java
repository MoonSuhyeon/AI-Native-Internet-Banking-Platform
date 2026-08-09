package com.bank.loan.advisory.engine.rules;

import com.bank.loan.advisory.engine.AdvisoryRule;
import com.bank.loan.advisory.engine.EvaluationMode;
import com.bank.loan.advisory.engine.RuleContext;
import com.bank.loan.advisory.engine.RuleResult;
import com.bank.loan.advisory.engine.SignalSpec;
import com.bank.loan.dsr.domain.DsrCalculation;
import com.bank.loan.dsr.repository.DsrCalculationRepository;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.repository.LoanReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * DSR 한도 초과 승인 룰. CRITICAL.
 * 트리거: DSR_CALCULATION.dsr_status_cd='FAIL'(=OVER_LIMIT) 이면서 본심사 결정이 APPROVED.
 * 미해결 시 약정 생성 게이트가 작동해 후속 단계 진행을 차단한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DsrThresholdOverrideRule implements AdvisoryRule {

    public static final String RULE_CD = "DSR_THRESHOLD_OVERRIDE";

    private final LoanReviewRepository reviewRepo;
    private final DsrCalculationRepository dsrRepo;

    @Override
    public String ruleCd() {
        return RULE_CD;
    }

    @Override
    public boolean supports(EvaluationMode mode) {
        return mode == EvaluationMode.SYNC;
    }

    @Override
    public List<RuleResult> evaluate(RuleContext context) {
        LoanReview review = reviewRepo.findById(context.revId())
                .filter(r -> r.getDeletedAt() == null)
                .orElse(null);
        if (review == null || !LoanReview.DECISION_APPROVED.equals(review.getRevDecisionCd())) {
            return List.of();
        }

        DsrCalculation dsr = dsrRepo.findByApplIdAndDeletedAtIsNull(review.getApplId()).orElse(null);
        if (dsr == null || !DsrCalculation.STATUS_FAIL.equals(dsr.getDsrStatusCd())) {
            return List.of();
        }

        SignalSpec signal = SignalSpec.builder()
                .signalKindCd("DSR_OVERRIDE")
                .signalMetric("dsr_ratio_bps")
                .observedValue(BigDecimal.valueOf(dsr.getDsrRatioBps()))
                .thresholdValue(BigDecimal.valueOf(dsr.getDsrLimitBps()))
                .observedAt(OffsetDateTime.now())
                .build();

        String summary = String.format(
                "DSR %d bps 가 한도 %d bps 를 초과(STATUS=FAIL)했으나 본심사가 승인되었습니다. 정책 예외 검토가 필요합니다.",
                dsr.getDsrRatioBps(), dsr.getDsrLimitBps());

        return List.of(RuleResult.builder()
                .revId(review.getRevId())
                .advisoryTypeCd("REREVIEW_RECOMMEND")
                .severityCd("CRITICAL")
                .advrTitle("DSR 한도 초과 승인")
                .advrSummary(summary)
                .targetReviewerId(review.getReviewerId())
                .signals(List.of(signal))
                .build());
    }
}
