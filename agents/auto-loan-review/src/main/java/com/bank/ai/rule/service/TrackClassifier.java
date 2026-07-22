package com.bank.ai.rule.service;

import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.rule.domain.HardFailReason;
import com.bank.ai.rule.domain.Track;
import com.bank.ai.rule.domain.TrackDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 트랙 분기 결정 — banking-review-llm §4·6 + pd-label-acquisition §5.3 결합 분기.
 *
 * <p>듀얼 score 모드 (decisionScore != null, PD 모델 가용):
 * <pre>
 * Hard fail                                          → Track 2 (자동 반려)
 * decisionScore ≥ τ_strong ∧ pd ≤ safetyTau          → Track 1 (강한 자동 승인)
 * pd > τ ∨ decisionScore ≤ τ_reject                  → Track 2 (자동 반려)
 * 그 외                                                → Track 3 (사람 심사)
 * </pre>
 *
 * <p>PD-only 폴백 (decisionScore == null):
 * <pre>
 * Hard fail                              → Track 2
 * pd ≤ safetyTau                         → Track 1
 * pd ≤ τ                                 → Track 3
 * pd > τ                                 → Track 2
 * </pre>
 *
 * <p>임계치는 모두 정책 (RuleEngineProperties / PolicyMatrix). 본 클래스의 분기 로직 자체는
 * 거버넌스 동결 대상 — 변경 시 신용정책위 의결 필요.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrackClassifier {

    private final HardConstraintEvaluator hardConstraintEvaluator;
    private final PolicyMatrix policyMatrix;

    /** PD-only 폴백 API (1.10 호환). */
    public TrackDecision classify(AutoReviewRequest req, double pd) {
        return classify(req, pd, null);
    }

    public TrackDecision classify(AutoReviewRequest req, double pd, Double decisionScore) {
        List<HardFailReason> hardFails = hardConstraintEvaluator.evaluate(req);
        double threshold = policyMatrix.lookup(req.productCode(), req.applicantSegment());
        double safetyTau = policyMatrix.safetyMarginThreshold(req.productCode(), req.applicantSegment());

        if (!hardFails.isEmpty()) {
            String reasons = hardFails.stream()
                    .map(HardFailReason::message)
                    .collect(Collectors.joining(", "));
            String rationale = "Hard fail %d건 → 자동 반려 (%s)".formatted(hardFails.size(), reasons);
            log.info("track=TRACK_2 hardFails={} product={} segment={}",
                    hardFails.stream().map(HardFailReason::code).toList(),
                    req.productCode(), req.applicantSegment());
            return new TrackDecision(Track.TRACK_2, hardFails, pd, decisionScore,
                    threshold, safetyTau, rationale);
        }

        Track track;
        String rationale;
        if (decisionScore == null) {
            // PD-only 폴백 (1.10 호환)
            if (pd <= safetyTau) {
                track = Track.TRACK_1;
                rationale = "PD %.4f ≤ 안전여유 임계 %.4f → 자동 승인 권고".formatted(pd, safetyTau);
            } else if (pd <= threshold) {
                track = Track.TRACK_3;
                rationale = "PD %.4f ≤ 매트릭스 임계 %.4f (안전여유 %.4f 초과) → 사람 심사 필수"
                        .formatted(pd, threshold, safetyTau);
            } else {
                track = Track.TRACK_2;
                rationale = "PD %.4f > 매트릭스 임계 %.4f → 자동 반려 (PD 초과)"
                        .formatted(pd, threshold);
            }
        } else {
            // 듀얼 결합 분기 (pd-label-acquisition §5.3)
            double decStrong = policyMatrix.decisionStrongThreshold(req.productCode(), req.applicantSegment());
            double decReject = policyMatrix.decisionRejectThreshold(req.productCode(), req.applicantSegment());

            if (decisionScore >= decStrong && pd <= safetyTau) {
                track = Track.TRACK_1;
                rationale = ("PD %.4f ≤ 안전여유 %.4f ∧ 결정신뢰 %.4f ≥ %.2f "
                        + "→ 강한 자동 승인 권고")
                        .formatted(pd, safetyTau, decisionScore, decStrong);
            } else if (pd > threshold) {
                track = Track.TRACK_2;
                rationale = "PD %.4f > 매트릭스 임계 %.4f → 자동 반려 (PD 초과)"
                        .formatted(pd, threshold);
            } else if (decisionScore <= decReject) {
                track = Track.TRACK_2;
                rationale = ("결정신뢰 %.4f ≤ %.2f → 자동 반려 "
                        + "(PD %.4f 는 매트릭스 이하지만 결정 모델이 낮은 승인 신뢰 보고)")
                        .formatted(decisionScore, decReject, pd);
            } else {
                track = Track.TRACK_3;
                rationale = ("PD %.4f ≤ 매트릭스 %.4f ∧ 결정신뢰 %.4f 회색지대 "
                        + "→ 사람 심사 필수")
                        .formatted(pd, threshold, decisionScore);
            }
        }

        log.info("track={} product={} segment={} pd={} decisionScore={} threshold={} safetyTau={}",
                track, req.productCode(), req.applicantSegment(),
                pd, decisionScore, threshold, safetyTau);

        return new TrackDecision(track, hardFails, pd, decisionScore,
                threshold, safetyTau, rationale);
    }
}
