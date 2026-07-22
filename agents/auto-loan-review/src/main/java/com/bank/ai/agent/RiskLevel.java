package com.bank.ai.agent;

import com.bank.ai.rule.domain.Track;

/**
 * 에이전트 리스크 수준 — pre-review-agent-plan.md §출력.
 *
 * <p>Track 에서 기계적으로 파생 (외부 노출 없이 내부 분류용).
 */
public enum RiskLevel {
    /** Track 1 — PD 안전여유 이하, 자동 승인 후보 */
    LOW,
    /** Track 3 — PD 회색지대, 심층 심사 필요 */
    MEDIUM,
    /** Track 2 — hard fail 또는 high-PD */
    HIGH;

    public static RiskLevel from(Track track) {
        return switch (track) {
            case TRACK_1 -> LOW;
            case TRACK_2 -> HIGH;
            case TRACK_3 -> MEDIUM;
        };
    }
}
