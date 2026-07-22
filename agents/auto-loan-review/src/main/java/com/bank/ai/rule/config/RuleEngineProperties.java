package com.bank.ai.rule.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

/**
 * 자동심사 RuleEngine 정책 매개변수 — application.yml 의 {@code ai.rule-engine} 섹션과 바인딩.
 *
 * <p>본 값은 자행 신용정책서·신용정책위원회 의결사항에 해당하며, 코드 변경 없이
 * application.yml 만 수정해 분기 리뷰로 갱신한다. plan {@code banking-review-llm.md §4·7} 참조.
 *
 * <p>변경 시 영향:
 * <ul>
 *   <li>{@code hardConstraints} — Hard fail 판정 (Track 2 즉시 반려)</li>
 *   <li>{@code pdThresholdMatrix} — 상품·세그먼트별 PD 임계치 (트랙 분기 기준)</li>
 *   <li>{@code safetyMarginRatio} — Track 1 자동승인 여유 비율 (pd ≤ τ × ratio)</li>
 * </ul>
 */
@Validated
@ConfigurationProperties(prefix = "ai.rule-engine")
public record RuleEngineProperties(
        @NotNull @Valid HardConstraints hardConstraints,
        @DecimalMin("0.0") @DecimalMax("1.0") double safetyMarginRatio,
        @DecimalMin("0.0") @DecimalMax("1.0") double defaultPdThreshold,
        @NotNull Map<String, Map<String, Double>> pdThresholdMatrix,
        /**
         * decision 모델 강한 승인 임계 — P(APPROVE) ≥ 본 값 이면서 PD 도 안전여유 통과 시 Track 1.
         * plan §5.3 결합 분기. PD 모델 미가용 시 본 값은 무시되고 PD-only 폴백 로직 사용.
         */
        @DecimalMin("0.0") @DecimalMax("1.0") double decisionStrongThreshold,
        /**
         * decision 모델 약한 신뢰 임계 — P(APPROVE) ≤ 본 값 이면 Track 2 (자동 반려) 강화 사유.
         * PD 모델이 잘 못 잡는 케이스(LTV/저소득 등) 의 보조 reject 신호.
         */
        @DecimalMin("0.0") @DecimalMax("1.0") double decisionRejectThreshold,
        /**
         * Kill switch — false 면 /evaluate 가 즉시 503 AI_003 반환.
         * 사고·드리프트·정책 변경 직후 등 자동심사 일시 중단 시 사용.
         */
        boolean enabled,
        /**
         * Shadow 모드 — true 면 추론·트랙 분기 모두 실행하지만 응답에 shadow=true 표시.
         * plan §9 운영 첫 2개월 사람 심사 일치율 측정용. 클라이언트(loan-service)는 본 플래그를
         * 보고 LOAN_REVIEW 적재 정책을 결정 (현재는 항상 적재).
         */
        boolean shadowMode
) {

    /**
     * 정책서 hard constraint 한도 — 위반 시 Track 2 자동반려.
     */
    public record HardConstraints(
            @DecimalMin("0.0") @DecimalMax("2.0") double dsrMax,
            @DecimalMin("0.0") @DecimalMax("2.0") double ltvMax,
            @Min(0) int creditScoreMin,
            @Min(0) int delinquency24mMax,
            @Min(0) int ageMin
    ) {
    }
}
