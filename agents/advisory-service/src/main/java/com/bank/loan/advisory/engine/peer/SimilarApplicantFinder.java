package com.bank.loan.advisory.engine.peer;

import com.bank.loan.creditevaluation.domain.CreditEvaluation;
import com.bank.loan.creditevaluation.repository.CreditEvaluationRepository;
import com.bank.loan.dsr.domain.DsrCalculation;
import com.bank.loan.dsr.repository.DsrCalculationRepository;
import com.bank.loan.ltv.domain.LtvCalculation;
import com.bank.loan.ltv.repository.LtvCalculationRepository;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.repository.LoanReviewRepository;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 유사 신청자 매칭 헬퍼. 본 건의 (creditScore, dsrRatioBps, ltvRatioBps) 와
 * ±윈도우 안에 있는 최근 N일 본심사들을 찾아낸다 — PEER_DECISION_DIVERGENCE 룰의 입력.
 *
 * MVP 단순 in-memory 매칭. 운영 데이터 누적 시 native query + 부분 인덱스로 교체 가능.
 *
 * 매칭 윈도우:
 *   - creditScore : ±5점
 *   - dsrRatioBps : ±500bps (5%)
 *   - ltvRatioBps : ±500bps (5%) — LTV 가 있는 건끼리만 비교, 없으면 무시
 */
@Component
@RequiredArgsConstructor
public class SimilarApplicantFinder {

    private static final int CREDIT_SCORE_WINDOW = 5;
    private static final int DSR_BPS_WINDOW = 500;
    private static final int LTV_BPS_WINDOW = 500;

    private final LoanReviewRepository reviewRepo;
    private final CreditEvaluationRepository creditEvalRepo;
    private final DsrCalculationRepository dsrRepo;
    private final LtvCalculationRepository ltvRepo;

    /**
     * 본 건과 유사한 과거 본심사를 반환. 본 건은 결과에서 제외.
     * @param target  본 건의 프로파일
     * @param from    윈도우 시작(inclusive) — reviewed_at 기준
     * @param to      윈도우 끝(exclusive)
     * @param excludeRevId 결과에서 제외할 본심사 ID (보통 본 건의 revId)
     */
    public List<SimilarReview> findSimilar(SimilarApplicantQuery target,
                                           OffsetDateTime from, OffsetDateTime to,
                                           Long excludeRevId) {
        List<LoanReview> reviews = reviewRepo
                .findByReviewedAtGreaterThanEqualAndReviewedAtLessThanAndDeletedAtIsNull(from, to)
                .stream()
                .filter(r -> LoanReview.STATUS_COMPLETED.equals(r.getRevStatusCd()))
                .filter(r -> !Objects.equals(r.getRevId(), excludeRevId))
                .toList();
        if (reviews.isEmpty()) return List.of();

        List<Long> applIds = reviews.stream().map(LoanReview::getApplId).distinct().toList();
        Map<Long, CreditEvaluation> credits = new HashMap<>();
        Map<Long, DsrCalculation> dsrs = new HashMap<>();
        Map<Long, LtvCalculation> ltvs = new HashMap<>();
        for (Long applId : applIds) {
            creditEvalRepo.findByApplIdAndDeletedAtIsNull(applId).ifPresent(c -> credits.put(applId, c));
            dsrRepo.findByApplIdAndDeletedAtIsNull(applId).ifPresent(d -> dsrs.put(applId, d));
            // LTV 는 신청당 여러 건일 수 있음 — 가장 큰 ratio (worst) 만 비교
            ltvRepo.findByApplIdAndDeletedAtIsNullOrderByLtvRatioBpsDesc(applId).stream()
                    .findFirst().ifPresent(l -> ltvs.put(applId, l));
        }

        List<SimilarReview> out = new ArrayList<>();
        for (LoanReview r : reviews) {
            CreditEvaluation c = credits.get(r.getApplId());
            DsrCalculation d = dsrs.get(r.getApplId());
            if (c == null || c.getCevalScore() == null || d == null) continue;

            if (Math.abs(c.getCevalScore() - target.creditScore()) > CREDIT_SCORE_WINDOW) continue;
            if (Math.abs(d.getDsrRatioBps() - target.dsrRatioBps()) > DSR_BPS_WINDOW) continue;

            LtvCalculation l = ltvs.get(r.getApplId());
            if (target.ltvRatioBps() != null && l != null) {
                if (Math.abs(l.getLtvRatioBps() - target.ltvRatioBps()) > LTV_BPS_WINDOW) continue;
            }

            out.add(SimilarReview.builder()
                    .revId(r.getRevId())
                    .applId(r.getApplId())
                    .decisionCd(r.getRevDecisionCd())
                    .creditScore(c.getCevalScore())
                    .dsrRatioBps(d.getDsrRatioBps())
                    .ltvRatioBps(l != null ? l.getLtvRatioBps() : null)
                    .reviewedAt(r.getReviewedAt())
                    .build());
        }
        return out;
    }

    @Builder
    public record SimilarApplicantQuery(Integer creditScore, Integer dsrRatioBps, Integer ltvRatioBps) {}

    @Builder
    public record SimilarReview(
            Long revId, Long applId, String decisionCd,
            Integer creditScore, Integer dsrRatioBps, Integer ltvRatioBps,
            OffsetDateTime reviewedAt
    ) {}
}
