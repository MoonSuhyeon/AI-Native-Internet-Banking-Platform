package com.bank.ai.rule.domain;

import java.util.List;

/**
 * RuleEngine 의 최종 트랙 분기 결과 — banking-review-llm §6, §8.
 *
 * <p>audit_log 영구 보존 대상. 동일 입력 + 동일 정책으로 결정 재현 가능해야 함.
 *
 * @param track                  분기된 트랙
 * @param hardFails              Hard constraint 위반 사유 (없으면 비어있음)
 * @param pd                     PD score (Phase 1.4-PD 부터는 homecredit_pd 모델 출력;
 *                                미가용 시 HMDA decision 모델의 P(REJECT) 폴백)
 * @param decisionScore          HMDA decision 모델의 P(APPROVE). 듀얼 결합 분기에 사용. null 가능.
 * @param pdThreshold            (product, segment) 정책 매트릭스 임계치
 * @param safetyMarginThreshold  Track 1 진입 여유 임계 (pdThreshold × safetyMarginRatio)
 * @param rationale              심사원·LLM 리포트용 한국어 근거 한 줄
 */
public record TrackDecision(
        Track track,
        List<HardFailReason> hardFails,
        double pd,
        Double decisionScore,
        double pdThreshold,
        double safetyMarginThreshold,
        String rationale
) {
    /**
     * audit_log 직렬화 편의용 hard fail 코드 리스트.
     */
    public List<String> hardFailCodes() {
        return hardFails.stream().map(HardFailReason::code).toList();
    }
}
