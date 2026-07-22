package com.bank.loan.advisory.engine.rules;

import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.domain.ReviewerDecisionSnapshot;
import com.bank.loan.advisory.engine.AdvisoryRule;
import com.bank.loan.advisory.engine.EvaluationMode;
import com.bank.loan.advisory.engine.RuleContext;
import com.bank.loan.advisory.engine.RuleResult;
import com.bank.loan.advisory.engine.SignalSpec;
import com.bank.loan.advisory.repository.ReviewerDecisionSnapshotRepository;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.repository.LoanReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 심사관 거절율 편차 룰. WARN.
 * 트리거: 스냅샷의 deviation_sigma >= +2.0 (해당 코호트에서 동료 평균 대비 +2σ 초과),
 *         최소 표본 30건. 대상 reviewer 의 해당 일자 본심사 건마다 리포트 발행.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BiasRejectRateDeviationRule implements AdvisoryRule {

    public static final String RULE_CD = "BIAS_REJECT_RATE_DEVIATION";
    private static final BigDecimal SIGMA_THRESHOLD = BigDecimal.valueOf(2.0);
    private static final int MIN_SAMPLE = 30;
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ReviewerDecisionSnapshotRepository snapshotRepo;
    private final LoanReviewRepository reviewRepo;

    @Override
    public String ruleCd() {
        return RULE_CD;
    }

    @Override
    public boolean supports(EvaluationMode mode) {
        return mode == EvaluationMode.BATCH;
    }

    @Override
    public List<RuleResult> evaluate(RuleContext context) {
        String baseDate = context.snapshotDate();
        List<ReviewerDecisionSnapshot> snapshots = snapshotRepo.findBySnapshotDateOrderByReviewerIdAsc(baseDate);
        if (snapshots.isEmpty()) return List.of();

        Map<Long, List<ReviewerDecisionSnapshot>> triggeredByReviewer = new HashMap<>();
        for (ReviewerDecisionSnapshot s : snapshots) {
            if (s.getDeviationSigma() == null) continue;
            if (s.getTotalReviewCount() < MIN_SAMPLE) continue;
            if (s.getDeviationSigma().compareTo(SIGMA_THRESHOLD) < 0) continue;
            triggeredByReviewer.computeIfAbsent(s.getReviewerId(), k -> new ArrayList<>()).add(s);
        }
        if (triggeredByReviewer.isEmpty()) return List.of();

        LocalDate date = LocalDate.parse(baseDate, YYYYMMDD);
        OffsetDateTime start = date.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = start.plusDays(1);
        List<LoanReview> dayReviews = reviewRepo
                .findByReviewedAtGreaterThanEqualAndReviewedAtLessThanAndDeletedAtIsNull(start, end);

        List<RuleResult> results = new ArrayList<>();
        for (LoanReview review : dayReviews) {
            if (review.getReviewerId() == null) continue;
            List<ReviewerDecisionSnapshot> cohorts = triggeredByReviewer.get(review.getReviewerId());
            if (cohorts == null) continue;

            List<SignalSpec> signals = new ArrayList<>();
            for (ReviewerDecisionSnapshot s : cohorts) {
                signals.add(SignalSpec.builder()
                        .signalKindCd("REJECT_RATE_DEVIATION")
                        .signalMetric("reject_rate_bps")
                        .observedValue(BigDecimal.valueOf(s.getRejectRateBps()))
                        .thresholdValue(s.getPeerAvgRejectRateBps() != null
                                ? BigDecimal.valueOf(s.getPeerAvgRejectRateBps())
                                : null)
                        .peerBaselineValue(s.getPeerAvgRejectRateBps() != null
                                ? BigDecimal.valueOf(s.getPeerAvgRejectRateBps())
                                : null)
                        .sampleSize(s.getTotalReviewCount())
                        .observedWindowStart(baseDate)
                        .observedWindowEnd(baseDate)
                        .observedAt(s.getSnapshottedAt())
                        .signalDetailJson(String.format(
                                "{\"cohortDim\":\"%s\",\"cohortValue\":\"%s\",\"deviationSigma\":%s}",
                                s.getCohortDimensionCd(), s.getCohortValue(), s.getDeviationSigma()))
                        .build());
            }

            results.add(RuleResult.builder()
                    .revId(review.getRevId())
                    .advisoryTypeCd("BIAS_DETECTION")
                    .severityCd(ReviewAdvisoryReport.SEVERITY_WARN)
                    .advrTitle("심사관 거절율 편차 감지")
                    .advrSummary(String.format(
                            "심사관 %d 의 거절율이 동료 평균 대비 +2σ 초과 — 코호트 %d개 / 일자 %s",
                            review.getReviewerId(), cohorts.size(), baseDate))
                    .targetReviewerId(review.getReviewerId())
                    .signals(signals)
                    .build());
        }
        log.info("BIAS_REJECT_RATE_DEVIATION baseDate={} triggeredReviewers={} reports={}",
                baseDate, triggeredByReviewer.size(), results.size());
        return results;
    }
}
