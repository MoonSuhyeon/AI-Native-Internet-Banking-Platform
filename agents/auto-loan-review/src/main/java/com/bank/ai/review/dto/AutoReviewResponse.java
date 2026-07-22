package com.bank.ai.review.dto;

import java.util.Map;

/**
 * 자동심사 ML 추론 결과 (단건). 듀얼 모델 응답 — decision 모델 + PD 모델.
 *
 * <p>Phase 1.4-PD 이후 두 모델을 모두 호출한 결과:
 * <ul>
 *   <li>decision (HMDA): {@code APPROVE/REJECT} 분류 + 클래스별 확률</li>
 *   <li>PD (homecredit): 캘리브레이션된 P(default_within_12m=1)</li>
 * </ul>
 * PD 모델 미배포 환경에선 {@code pdScore} 와 {@code pdModelVersion} 이 null.
 *
 * <p>트랙 분기·hard constraint 까지 포함한 종합 결과는 {@link AutoReviewEvaluateResponse} 참조.
 */
public record AutoReviewResponse(
        String modelVersion,
        String decision,
        double score,
        Map<String, Double> proba,
        Double pdScore,
        String pdModelVersion
) {
    /** P(APPROVE) — 듀얼 결합 분기의 decisionScore. proba 누락 시 null. */
    public Double decisionScore() {
        return proba != null ? proba.get("APPROVE") : null;
    }
}
