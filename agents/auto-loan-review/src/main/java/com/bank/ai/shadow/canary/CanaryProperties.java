package com.bank.ai.shadow.canary;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Canary 라우팅 설정 — application.yml {@code ai.canary} 섹션.
 *
 * <p>shadow → canary → full 단계 전환 (E4-4).
 * {@code ai.canary.enabled=true} 시 {@link CanaryRouter} 빈 활성.
 * {@code es-weight=5} → 요청의 5% 가 ES RAG 경로로 라우팅.
 *
 * @param enabled        canary 라우팅 활성 여부
 * @param esWeight       ES 백엔드 라우팅 비율 (0~100 정수)
 * @param minShadowRuns  게이트 판정 최소 shadow 건수 — 미달 시 INSUFFICIENT_DATA
 */
@ConfigurationProperties(prefix = "ai.canary")
public record CanaryProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("5")     int esWeight,
        @DefaultValue("100")   int minShadowRuns
) {
    public CanaryProperties {
        if (esWeight < 0 || esWeight > 100) {
            throw new IllegalArgumentException("ai.canary.es-weight must be 0~100, got: " + esWeight);
        }
        if (minShadowRuns < 1) {
            throw new IllegalArgumentException("ai.canary.min-shadow-runs must be >= 1");
        }
    }
}
