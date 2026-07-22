package com.bank.ai.agent;

import com.bank.ai.rule.domain.TrackDecision;

/**
 * TrackDecision 으로부터 RiskLevel 파생 — pre-review-agent-plan.md §출력 risk_level 매핑.
 *
 * <p>{@link RiskLevel#from(com.bank.ai.rule.domain.Track)} 는 Track 단순 매핑이지만,
 * 본 클래스는 Track 3 에서 {@code decisionScore < 0.4} 일 때 HIGH 로 상향 보정한다.
 */
public final class RiskLevelDeriver {

    /** Track 3 고위험 보정 임계 — decision 모델 P(APPROVE) 이 이 값 미만이면 MEDIUM → HIGH */
    static final double DECISION_SCORE_LOW_THRESHOLD = 0.4;

    private RiskLevelDeriver() {}

    /**
     * @param decision RuleEngine 트랙 분기 결과
     * @return 파생된 리스크 수준
     */
    public static RiskLevel derive(TrackDecision decision) {
        return switch (decision.track()) {
            case TRACK_1 -> RiskLevel.LOW;
            case TRACK_2 -> RiskLevel.HIGH;
            case TRACK_3 -> {
                if (decision.decisionScore() != null
                        && decision.decisionScore() < DECISION_SCORE_LOW_THRESHOLD) {
                    yield RiskLevel.HIGH; // Track 3 + 낮은 decision → 고위험 보정
                }
                yield RiskLevel.MEDIUM;
            }
        };
    }
}
