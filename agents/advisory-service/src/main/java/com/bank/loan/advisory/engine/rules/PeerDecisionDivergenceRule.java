package com.bank.loan.advisory.engine.rules;

import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.engine.AdvisoryRule;
import com.bank.loan.advisory.engine.EvaluationMode;
import com.bank.loan.advisory.engine.RuleContext;
import com.bank.loan.advisory.engine.RuleResult;
import com.bank.loan.advisory.engine.SignalSpec;
import com.bank.loan.advisory.engine.peer.SimilarApplicantFinder;
import com.bank.loan.advisory.engine.peer.SimilarApplicantFinder.SimilarApplicantQuery;
import com.bank.loan.advisory.engine.peer.SimilarApplicantFinder.SimilarReview;
import com.bank.loan.creditevaluation.repository.CreditEvaluationRepository;
import com.bank.loan.dsr.repository.DsrCalculationRepository;
import com.bank.loan.ltv.repository.LtvCalculationRepository;
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
import java.util.List;

/**
 * 유사 신청자 결정 분기 룰. WARN.
 * 트리거: 본 건의 (creditScore±5, DSR±500bps, LTV±500bps) 와 매칭되는 최근 90일 본심사 그룹에서
 *         (APPROVED/REJECTED) 결정이 70:30 이상으로 한쪽으로 쏠려있고, 본 건이 소수 측인 경우.
 *         표본 < {@value #MIN_MATCH_COUNT} 건이면 통계적 의미가 부족해 skip.
 *
 * BATCH 모드 — 일배치 시 해당 일자에 처리된 본심사 각각에 대해 평가.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PeerDecisionDivergenceRule implements AdvisoryRule {

    public static final String RULE_CD = "PEER_DECISION_DIVERGENCE";
    private static final int MIN_MATCH_COUNT = 10;
    private static final double MAJORITY_THRESHOLD = 0.70;
    private static final int LOOKBACK_DAYS = 90;
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final LoanReviewRepository reviewRepo;
    private final CreditEvaluationRepository creditEvalRepo;
    private final DsrCalculationRepository dsrRepo;
    private final LtvCalculationRepository ltvRepo;
    private final SimilarApplicantFinder finder;

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
        LocalDate date = LocalDate.parse(baseDate, YYYYMMDD);
        OffsetDateTime dayStart = date.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime dayEnd = dayStart.plusDays(1);
        OffsetDateTime windowStart = dayStart.minusDays(LOOKBACK_DAYS);

        List<LoanReview> dayReviews = reviewRepo
                .findByReviewedAtGreaterThanEqualAndReviewedAtLessThanAndDeletedAtIsNull(dayStart, dayEnd)
                .stream()
                .filter(r -> LoanReview.STATUS_COMPLETED.equals(r.getRevStatusCd()))
                .filter(r -> LoanReview.DECISION_APPROVED.equals(r.getRevDecisionCd())
                        || LoanReview.DECISION_REJECTED.equals(r.getRevDecisionCd()))
                .toList();
        if (dayReviews.isEmpty()) return List.of();

        List<RuleResult> results = new ArrayList<>();
        for (LoanReview review : dayReviews) {
            var ceval = creditEvalRepo.findByApplIdAndDeletedAtIsNull(review.getApplId()).orElse(null);
            var dsr = dsrRepo.findByApplIdAndDeletedAtIsNull(review.getApplId()).orElse(null);
            if (ceval == null || ceval.getCevalScore() == null || dsr == null) continue;
            Integer ltvRatioBps = ltvRepo
                    .findByApplIdAndDeletedAtIsNullOrderByLtvRatioBpsDesc(review.getApplId())
                    .stream().findFirst().map(l -> l.getLtvRatioBps()).orElse(null);

            List<SimilarReview> matches = finder.findSimilar(
                    SimilarApplicantQuery.builder()
                            .creditScore(ceval.getCevalScore())
                            .dsrRatioBps(dsr.getDsrRatioBps())
                            .ltvRatioBps(ltvRatioBps)
                            .build(),
                    windowStart, dayEnd, review.getRevId());
            if (matches.size() < MIN_MATCH_COUNT) continue;

            long approved = matches.stream().filter(m -> LoanReview.DECISION_APPROVED.equals(m.decisionCd())).count();
            long rejected = matches.stream().filter(m -> LoanReview.DECISION_REJECTED.equals(m.decisionCd())).count();
            long deciding = approved + rejected;
            if (deciding < MIN_MATCH_COUNT) continue;

            double approveRatio = (double) approved / deciding;
            boolean majorityApprove = approveRatio >= MAJORITY_THRESHOLD;
            boolean majorityReject = approveRatio <= (1.0 - MAJORITY_THRESHOLD);
            if (!majorityApprove && !majorityReject) continue;

            boolean meIsApprove = LoanReview.DECISION_APPROVED.equals(review.getRevDecisionCd());
            boolean meIsMinority = (majorityApprove && !meIsApprove) || (majorityReject && meIsApprove);
            if (!meIsMinority) continue;

            BigDecimal observedRatio = BigDecimal.valueOf(approveRatio).setScale(4, RoundingMode.HALF_UP);
            SignalSpec signal = SignalSpec.builder()
                    .signalKindCd("PEER_DECISION_DIVERGENCE")
                    .signalMetric("peer_approve_ratio")
                    .observedValue(observedRatio)
                    .thresholdValue(BigDecimal.valueOf(MAJORITY_THRESHOLD))
                    .peerBaselineValue(BigDecimal.valueOf(majorityApprove ? approved : rejected)
                            .divide(BigDecimal.valueOf(deciding), 4, RoundingMode.HALF_UP))
                    .sampleSize((int) deciding)
                    .observedWindowStart(YYYYMMDD.format(date.minusDays(LOOKBACK_DAYS)))
                    .observedWindowEnd(baseDate)
                    .observedAt(OffsetDateTime.now())
                    .signalDetailJson(String.format(
                            "{\"approved\":%d,\"rejected\":%d,\"meDecision\":\"%s\",\"creditScore\":%d,\"dsrRatioBps\":%d}",
                            approved, rejected, review.getRevDecisionCd(),
                            ceval.getCevalScore(), dsr.getDsrRatioBps()))
                    .build();

            results.add(RuleResult.builder()
                    .revId(review.getRevId())
                    .advisoryTypeCd("REREVIEW_RECOMMEND")
                    .severityCd(ReviewAdvisoryReport.SEVERITY_WARN)
                    .advrTitle("유사 신청자 결정 분기")
                    .advrSummary(String.format(
                            "유사 프로파일 %d건 중 %s %d:%d 분기 (다수 %d%%), 본 건은 소수 측 결정",
                            deciding,
                            majorityApprove ? "APPROVED" : "REJECTED",
                            majorityApprove ? approved : rejected,
                            majorityApprove ? rejected : approved,
                            (int) Math.round(Math.max(approveRatio, 1 - approveRatio) * 100)))
                    .targetReviewerId(review.getReviewerId())
                    .signals(List.of(signal))
                    .build());
        }
        log.info("PEER_DECISION_DIVERGENCE baseDate={} dayReviews={} reports={}",
                baseDate, dayReviews.size(), results.size());
        return results;
    }
}
