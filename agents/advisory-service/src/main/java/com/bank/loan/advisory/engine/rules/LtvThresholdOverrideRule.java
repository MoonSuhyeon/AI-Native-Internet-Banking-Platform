package com.bank.loan.advisory.engine.rules;

import com.bank.loan.advisory.engine.AdvisoryRule;
import com.bank.loan.advisory.engine.EvaluationMode;
import com.bank.loan.advisory.engine.RuleContext;
import com.bank.loan.advisory.engine.RuleResult;
import com.bank.loan.advisory.engine.SignalSpec;
import com.bank.loan.ltv.domain.LtvCalculation;
import com.bank.loan.ltv.repository.LtvCalculationRepository;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.repository.LoanReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * LTV 한도 초과 승인 룰. CRITICAL.
 * 트리거: LTV_CALCULATION.ltv_status_cd='FAIL'(=OVER_LIMIT) 인 row 가 1건 이상이면서 본심사 결정이 APPROVED.
 * 신청에 담보가 여러 개면 가장 큰 ratio 인 row 1개로 signal 을 만든다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LtvThresholdOverrideRule implements AdvisoryRule {

    public static final String RULE_CD = "LTV_THRESHOLD_OVERRIDE";

    private final LoanReviewRepository reviewRepo;
    private final LtvCalculationRepository ltvRepo;

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

        LtvCalculation worst = ltvRepo
                .findByApplIdAndDeletedAtIsNullOrderByLtvRatioBpsDesc(review.getApplId())
                .stream()
                .filter(l -> LtvCalculation.STATUS_FAIL.equals(l.getLtvStatusCd()))
                .findFirst()
                .orElse(null);
        if (worst == null) {
            return List.of();
        }

        SignalSpec signal = SignalSpec.builder()
                .signalKindCd("LTV_OVERRIDE")
                .signalMetric("ltv_ratio_bps")
                .observedValue(BigDecimal.valueOf(worst.getLtvRatioBps()))
                .thresholdValue(BigDecimal.valueOf(worst.getLtvLimitBps()))
                .observedAt(OffsetDateTime.now())
                .build();

        String summary = String.format(
                "LTV %d bps 가 한도 %d bps 를 초과(STATUS=FAIL, colId=%d)했으나 본심사가 승인되었습니다. 정책 예외 검토가 필요합니다.",
                worst.getLtvRatioBps(), worst.getLtvLimitBps(), worst.getColId());

        return List.of(RuleResult.builder()
                .revId(review.getRevId())
                .advisoryTypeCd("REREVIEW_RECOMMEND")
                .severityCd("CRITICAL")
                .advrTitle("LTV 한도 초과 승인")
                .advrSummary(summary)
                .targetReviewerId(review.getReviewerId())
                .signals(List.of(signal))
                .build());
    }
}
