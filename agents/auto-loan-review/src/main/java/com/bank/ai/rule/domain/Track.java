package com.bank.ai.rule.domain;

/**
 * 자동심사 트랙 분기 — banking-review-llm §1, §3, §7.
 *
 * <p>심사원 처리 방식과 SLA 가 트랙별로 다르다.
 */
public enum Track {
    /**
     * 자동 승인 권고. PD ≪ 매트릭스 임계 (safety margin 적용) + 모든 정책 룰 통과.
     * 심사원은 sign-off 만 (목표 5분/건).
     */
    TRACK_1("자동 승인 권고"),

    /**
     * 자동 반려 권고. Hard fail 또는 PD > 매트릭스 임계.
     * 심사원은 사유 확인 + 통보 (목표 3분/건).
     */
    TRACK_2("자동 반려 권고"),

    /**
     * 사람 심사 필수. PD 가 매트릭스 임계 이하지만 safety margin 미달.
     * 심사원이 자동 분석 결과 + RAG 검색 보고 심층 판단 (30분~수시간/건).
     */
    TRACK_3("사람 심사 필수");

    private final String displayName;

    Track(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
