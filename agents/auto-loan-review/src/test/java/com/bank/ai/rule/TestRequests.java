package com.bank.ai.rule;

import com.bank.ai.review.dto.AutoReviewRequest;

/**
 * 테스트용 AutoReviewRequest 픽스처. record 의 29 필드를 매번 채우는 부담 제거.
 * 검증 관심사 외 필드는 모두 null — ML 모델의 missing 분기 가정.
 */
public final class TestRequests {

    private TestRequests() {
    }

    /** 정책 위반 0건의 정상 신청자 (주담대, regular segment). */
    public static AutoReviewRequest healthy() {
        return baseline(0.30, 0.50, 750, 0, 35, "MORT_001", "regular");
    }

    /** hard constraint·트랙 분기 입력에 필요한 7 필드만 받아 정상값으로 나머지 채움. */
    public static AutoReviewRequest baseline(
            Double dsr, Double ltv, Integer creditScore, Integer delinq,
            Integer age, String productCode, String segment
    ) {
        return new AutoReviewRequest(
                // revId (Phase 1.6 콜백용 — 테스트에서는 null)
                null,
                // ---- Layer 1 (segment 외 모두 null) ----
                null, age, null, null, null, null, null, null,
                null, null, null, segment,
                // ---- Layer 2 ----
                null, null, null, null, null, null, dsr, ltv, null, null, delinq, creditScore,
                // ---- Layer 3 ----
                productCode, null, null, null, null,
                // ---- Layer 4 (PD 전용, 테스트는 미사용) ----
                null, null, null, null, null, null, null
        );
    }
}
