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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 심사관 승인율 편차 룰. WARN.
 * 트리거: 동일 코호트 내 다른 reviewer 들의 approve_rate_bps 대비 -2σ 미만(=과도한 승인 편향),
 *         최소 표본 30건. 본 룰은 snapshot.deviation_sigma (reject 기준) 가 아니라
 *         approve_rate_bps 의 peer 표준편차를 자체 계산한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BiasApprovalRateDeviationRule implements AdvisoryRule {

    public static final String RULE_CD = "BIAS_APPROVAL_RATE_DEVIATION";
    private static final double SIGMA_THRESHOLD = -2.0;
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

        // (cohortDim, cohortValue) 별 그룹
        Map<String, List<ReviewerDecisionSnapshot>> byCohort = new HashMap<>();
        for (ReviewerDecisionSnapshot s : snapshots) {
            String key = s.getCohortDimensionCd() + "|" + s.getCohortValue();
            byCohort.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }

        Map<Long, List<TriggeredSnapshot>> triggeredByReviewer = new HashMap<>();
        for (List<ReviewerDecisionSnapshot> cohort : byCohort.values()) {
            if (cohort.size() < 3) continue; // 본인 + 최소 2 peer 필요
            for (ReviewerDecisionSnapshot s : cohort) {
                if (s.getTotalReviewCount() < MIN_SAMPLE) continue;
                BigDecimal sigma = computeApproveSigma(s, cohort);
                if (sigma == null) continue;
                if (sigma.compareTo(BigDecimal.valueOf(SIGMA_THRESHOLD)) > 0) continue;
                triggeredByReviewer
                        .computeIfAbsent(s.getReviewerId(), k -> new ArrayList<>())
                        .add(new TriggeredSnapshot(s, sigma));
            }
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
            List<TriggeredSnapshot> cohorts = triggeredByReviewer.get(review.getReviewerId());
            if (cohorts == null) continue;

            List<SignalSpec> signals = new ArrayList<>();
            for (TriggeredSnapshot t : cohorts) {
                ReviewerDecisionSnapshot s = t.snapshot();
                Integer peerAvgApproveBps = computePeerAvgApprove(s, byCohort.get(s.getCohortDimensionCd() + "|" + s.getCohortValue()));
                signals.add(SignalSpec.builder()
                        .signalKindCd("APPROVAL_RATE_DEVIATION")
                        .signalMetric("approve_rate_bps")
                        .observedValue(BigDecimal.valueOf(s.getApproveRateBps()))
                        .thresholdValue(peerAvgApproveBps != null ? BigDecimal.valueOf(peerAvgApproveBps) : null)
                        .peerBaselineValue(peerAvgApproveBps != null ? BigDecimal.valueOf(peerAvgApproveBps) : null)
                        .sampleSize(s.getTotalReviewCount())
                        .observedWindowStart(baseDate)
                        .observedWindowEnd(baseDate)
                        .observedAt(s.getSnapshottedAt())
                        .signalDetailJson(String.format(
                                "{\"cohortDim\":\"%s\",\"cohortValue\":\"%s\",\"approveDeviationSigma\":%s}",
                                s.getCohortDimensionCd(), s.getCohortValue(), t.sigma()))
                        .build());
            }

            results.add(RuleResult.builder()
                    .revId(review.getRevId())
                    .advisoryTypeCd("BIAS_DETECTION")
                    .severityCd(ReviewAdvisoryReport.SEVERITY_WARN)
                    .advrTitle("심사관 승인율 편차 감지")
                    .advrSummary(String.format(
                            "심사관 %d 의 승인율이 동료 평균 대비 -2σ 미만 — 코호트 %d개 / 일자 %s",
                            review.getReviewerId(), cohorts.size(), baseDate))
                    .targetReviewerId(review.getReviewerId())
                    .signals(signals)
                    .build());
        }
        log.info("BIAS_APPROVAL_RATE_DEVIATION baseDate={} triggeredReviewers={} reports={}",
                baseDate, triggeredByReviewer.size(), results.size());
        return results;
    }

    private static BigDecimal computeApproveSigma(ReviewerDecisionSnapshot me, List<ReviewerDecisionSnapshot> cohort) {
        List<Integer> peers = cohort.stream()
                .filter(s -> !s.getReviewerId().equals(me.getReviewerId()))
                .map(ReviewerDecisionSnapshot::getApproveRateBps)
                .toList();
        if (peers.size() < 2) return null;
        double mean = peers.stream().mapToInt(Integer::intValue).average().orElse(0);
        double variance = peers.stream().mapToDouble(r -> Math.pow(r - mean, 2)).average().orElse(0);
        double std = Math.sqrt(variance);
        if (std == 0) return null;
        return BigDecimal.valueOf((me.getApproveRateBps() - mean) / std).setScale(4, RoundingMode.HALF_UP);
    }

    private static Integer computePeerAvgApprove(ReviewerDecisionSnapshot me, List<ReviewerDecisionSnapshot> cohort) {
        if (cohort == null) return null;
        double avg = cohort.stream()
                .filter(s -> !s.getReviewerId().equals(me.getReviewerId()))
                .mapToInt(ReviewerDecisionSnapshot::getApproveRateBps)
                .average().orElse(Double.NaN);
        return Double.isNaN(avg) ? null : (int) Math.round(avg);
    }

    private record TriggeredSnapshot(ReviewerDecisionSnapshot snapshot, BigDecimal sigma) {}
}
