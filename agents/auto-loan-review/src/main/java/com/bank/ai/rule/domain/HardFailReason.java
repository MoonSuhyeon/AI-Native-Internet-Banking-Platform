package com.bank.ai.rule.domain;

/**
 * 정책서 hard constraint 위반 사유 — 검출되면 즉시 Track 2 (자동 반려).
 *
 * <p>한국어 message 는 LLM 거절 통보 문구 생성 시 grounding 으로 사용되며,
 * code 는 audit_log·재현성용 식별자.
 */
public enum HardFailReason {
    DSR_EXCEEDED("DSR_EXCEEDED", "부채상환비율(DSR) 한도 초과"),
    LTV_EXCEEDED("LTV_EXCEEDED", "담보인정비율(LTV) 한도 초과"),
    CREDIT_SCORE_BELOW_MIN("CREDIT_SCORE_BELOW_MIN", "신용평점 최저 기준 미달"),
    DELINQUENCY_24M_PRESENT("DELINQUENCY_24M_PRESENT", "최근 24개월 진행 중 연체 이력"),
    AGE_BELOW_MIN("AGE_BELOW_MIN", "신청자 연령이 성인 기준 미달");

    private final String code;
    private final String message;

    HardFailReason(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
