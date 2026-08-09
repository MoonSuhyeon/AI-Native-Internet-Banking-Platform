package com.bank.loan.advisory.batch;

import com.bank.loan.advisory.domain.ReviewerDecisionSnapshot;
import com.bank.loan.advisory.repository.ReviewerDecisionSnapshotRepository;
import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.repository.LoanReviewRepository;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * 일배치 — 어제자 (또는 baseDate) `LOAN_REVIEW` 를 코호트 차원별로 집계해
 * `REVIEWER_DECISION_SNAPSHOT` 에 적재한다. BIAS_* 룰의 입력 데이터.
 *
 * 코호트 차원(MVP):
 *   - EMPLOYMENT_TYPE  : LoanApplication.employment_type_cd
 *   - LOAN_PURPOSE     : LoanApplication.loan_purpose_cd
 * (AGE_BAND / REGION 은 customer-service 호출 필요 — Phase 3 범위 밖, 추후 도입)
 *
 * 멱등 — 동일 (reviewer_id, snapshot_date, aggregation_window_cd, cohort_dimension_cd, cohort_value)
 * 가 이미 있으면 skip. 재집계 필요 시 운영자가 수동 삭제 후 재실행.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewerDecisionSnapshotService {

    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String WINDOW_DAILY = "DAILY";
    private static final int MINIMUM_SAMPLE_SIZE = 30;

    private final LoanReviewRepository reviewRepo;
    private final LoanApplicationRepository applicationRepo;
    private final ReviewerDecisionSnapshotRepository snapshotRepo;

    @Transactional
    public SnapshotRunResult runDailySnapshot(String baseDate) {
        LocalDate date = LocalDate.parse(baseDate, YYYYMMDD);
        OffsetDateTime start = date.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime end = start.plusDays(1);
        OffsetDateTime now = OffsetDateTime.now();

        List<LoanReview> reviews = reviewRepo
                .findByReviewedAtGreaterThanEqualAndReviewedAtLessThanAndDeletedAtIsNull(start, end)
                .stream()
                .filter(r -> LoanReview.STATUS_COMPLETED.equals(r.getRevStatusCd()))
                .toList();

        if (reviews.isEmpty()) {
            log.info("어드바이저리 스냅샷 — 대상 본심사 없음 baseDate={}", baseDate);
            return SnapshotRunResult.builder().baseDate(baseDate).reviewCount(0).inserted(0).skipped(0).build();
        }

        // applId → LoanApplication map (코호트 차원 추출)
        List<Long> applIds = reviews.stream().map(LoanReview::getApplId).distinct().toList();
        Map<Long, LoanApplication> appMap = new HashMap<>();
        for (LoanApplication app : applicationRepo.findAllById(applIds)) {
            appMap.put(app.getApplId(), app);
        }

        // (cohortDim, cohortValue) → (reviewerId → counts) 누적
        Map<CohortKey, Map<Long, Counts>> grouped = new HashMap<>();
        for (LoanReview review : reviews) {
            LoanApplication app = appMap.get(review.getApplId());
            if (app == null || review.getReviewerId() == null) continue;

            for (CohortKey cohort : extractCohorts(app)) {
                Map<Long, Counts> reviewerMap = grouped.computeIfAbsent(cohort, k -> new HashMap<>());
                Counts counts = reviewerMap.computeIfAbsent(review.getReviewerId(), k -> new Counts());
                counts.tally(review);
            }
        }

        int inserted = 0;
        int skipped = 0;
        for (Map.Entry<CohortKey, Map<Long, Counts>> e : grouped.entrySet()) {
            CohortKey cohort = e.getKey();
            Map<Long, Counts> reviewerMap = e.getValue();

            for (Map.Entry<Long, Counts> me : reviewerMap.entrySet()) {
                Long reviewerId = me.getKey();
                Counts mine = me.getValue();
                if (mine.total == 0) continue;

                // 멱등 — 이미 존재하면 skip
                boolean exists = snapshotRepo
                        .findByReviewerIdAndSnapshotDateOrderByCohortDimensionCdAscCohortValueAsc(reviewerId, baseDate)
                        .stream()
                        .anyMatch(s -> s.getAggregationWindowCd().equals(WINDOW_DAILY)
                                && s.getCohortDimensionCd().equals(cohort.dim)
                                && s.getCohortValue().equals(cohort.value));
                if (exists) {
                    skipped++;
                    continue;
                }

                // peer 평균/표준편차 — 동일 코호트의 다른 reviewer 들의 rejectRate
                List<Integer> peerRejectRates = new ArrayList<>();
                for (Map.Entry<Long, Counts> peer : reviewerMap.entrySet()) {
                    if (peer.getKey().equals(reviewerId)) continue;
                    Counts pc = peer.getValue();
                    if (pc.total == 0) continue;
                    peerRejectRates.add(rateBps(pc.reject, pc.total));
                }
                Integer peerAvgRejectBps = peerRejectRates.isEmpty() ? null
                        : (int) Math.round(peerRejectRates.stream().mapToInt(Integer::intValue).average().orElse(0));
                BigDecimal sigma = computeDeviationSigma(rateBps(mine.reject, mine.total), peerRejectRates);

                snapshotRepo.save(ReviewerDecisionSnapshot.builder()
                        .reviewerId(reviewerId)
                        .snapshotDate(baseDate)
                        .aggregationWindowCd(WINDOW_DAILY)
                        .cohortDimensionCd(cohort.dim)
                        .cohortValue(cohort.value)
                        .totalReviewCount(mine.total)
                        .approveCount(mine.approve)
                        .rejectCount(mine.reject)
                        .pendingCount(mine.pending)
                        .approveRateBps(rateBps(mine.approve, mine.total))
                        .rejectRateBps(rateBps(mine.reject, mine.total))
                        .peerAvgRejectRateBps(peerAvgRejectBps)
                        .deviationSigma(sigma)
                        .snapshottedAt(now)
                        .build());
                inserted++;
            }
        }

        log.info("어드바이저리 스냅샷 적재 완료 baseDate={} reviews={} inserted={} skipped={}",
                baseDate, reviews.size(), inserted, skipped);
        return SnapshotRunResult.builder()
                .baseDate(baseDate)
                .reviewCount(reviews.size())
                .inserted(inserted)
                .skipped(skipped)
                .build();
    }

    private static List<CohortKey> extractCohorts(LoanApplication app) {
        List<CohortKey> out = new ArrayList<>(2);
        if (app.getEmploymentTypeCd() != null) {
            out.add(new CohortKey("EMPLOYMENT_TYPE", app.getEmploymentTypeCd()));
        }
        if (app.getLoanPurposeCd() != null) {
            out.add(new CohortKey("LOAN_PURPOSE", app.getLoanPurposeCd()));
        }
        return out;
    }

    private static int rateBps(int count, int total) {
        return total == 0 ? 0 : (int) Math.round((double) count / total * 10_000d);
    }

    /**
     * peer 표준편차 기반 deviation_sigma 계산.
     * peer 표본 < {@link #MINIMUM_SAMPLE_SIZE}/10 이거나 표준편차가 0 이면 null (의미 없음).
     */
    private static BigDecimal computeDeviationSigma(int myRateBps, List<Integer> peerRates) {
        if (peerRates.size() < 2) return null;
        double mean = peerRates.stream().mapToInt(Integer::intValue).average().orElse(0);
        double variance = peerRates.stream()
                .mapToDouble(r -> Math.pow(r - mean, 2))
                .average().orElse(0);
        double std = Math.sqrt(variance);
        if (std == 0) return null;
        double sigma = (myRateBps - mean) / std;
        return BigDecimal.valueOf(sigma).setScale(4, RoundingMode.HALF_UP);
    }

    @Builder
    public record SnapshotRunResult(String baseDate, int reviewCount, int inserted, int skipped) {}

    private record CohortKey(String dim, String value) {}

    private static final class Counts {
        int total, approve, reject, pending;
        void tally(LoanReview review) {
            total++;
            String d = review.getRevDecisionCd();
            if (LoanReview.DECISION_APPROVED.equals(d)) approve++;
            else if (LoanReview.DECISION_REJECTED.equals(d)) reject++;
            else pending++;
        }
    }
}
