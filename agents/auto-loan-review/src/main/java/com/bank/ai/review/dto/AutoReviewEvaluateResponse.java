package com.bank.ai.review.dto;

import com.bank.ai.rule.domain.Track;
import com.bank.ai.rule.domain.TrackDecision;

import java.util.List;
import java.util.Map;

/**
 * 자동심사 종합 평가 응답 — ML 추론 + RuleEngine 트랙 분기 결과 통합.
 *
 * <p>심사원 대시보드 카드 + audit_log + LLM 리포트 grounding 의 source of truth.
 * 동일 입력 + 동일 모델·정책 버전이면 본 응답은 결정적이어야 한다.
 *
 * <p>Phase 1.4-PD 부터 듀얼 모델 score 노출:
 * <ul>
 *   <li>{@code pd} — PD 모델 출력 (homecredit), 미가용 시 decision 모델의 P(REJECT) 폴백</li>
 *   <li>{@code decisionScore} — decision 모델의 P(APPROVE). 결합 분기에 사용된 값. PD-only 폴백 시 null.</li>
 *   <li>{@code pdModelVersion} — PD 모델 버전 식별자. PD-only 폴백 시 null.</li>
 * </ul>
 */
public record AutoReviewEvaluateResponse(
        String modelVersion,                // decision 모델 버전
        String pdModelVersion,              // PD 모델 버전 (null = PD-only 폴백)
        double pd,                          // 트랙 분기 입력 (homecredit_pd 또는 P(REJECT) 폴백)
        Double decisionScore,               // P(APPROVE) — 듀얼 분기에 쓰인 값. null = PD-only.
        Map<String, Double> proba,          // decision 모델 전 클래스 확률
        String track,                       // TRACK_1 / TRACK_2 / TRACK_3
        String trackDisplayName,            // 한국어 노출명
        double pdThreshold,                 // 매트릭스 lookup 결과
        double safetyMarginThreshold,       // Track 1 진입 임계 (= threshold × ratio)
        List<String> hardFailCodes,         // audit 식별자
        List<String> hardFailMessages,      // 사람 읽기·LLM grounding
        String rationale,                   // 한국어 결정 근거 한 줄
        String reportStatus,                // Phase 1.6: PENDING / DONE / FAILED
        boolean shadow                      // plan §9 운영 첫 2개월 측정 모드
) {

    public static AutoReviewEvaluateResponse from(
            AutoReviewResponse inference, TrackDecision decision, boolean shadow, String reportStatus
    ) {
        Track t = decision.track();
        return new AutoReviewEvaluateResponse(
                inference.modelVersion(),
                inference.pdModelVersion(),
                decision.pd(),
                decision.decisionScore(),
                inference.proba(),
                t.name(),
                t.displayName(),
                decision.pdThreshold(),
                decision.safetyMarginThreshold(),
                decision.hardFailCodes(),
                decision.hardFails().stream().map(r -> r.message()).toList(),
                decision.rationale(),
                reportStatus,
                shadow
        );
    }
}
