package com.bank.ai.shadow.canary;

import com.bank.ai.metrics.AgentMetricsRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CanaryRouter 단위 테스트 — E4-4.
 *
 * <p>weight=0/100 는 결정론적 검증. weight=50 는 통계적 근사 검증 (1000 샘플, 40~60%).
 */
class CanaryRouterTest {

    private AgentMetricsRecorder metricsRecorder;

    @BeforeEach
    void setUp() {
        metricsRecorder = new AgentMetricsRecorder(new SimpleMeterRegistry());
    }

    @Test
    void weight_0_neverRoutesToEs() {
        CanaryRouter router = router(0);
        for (int i = 0; i < 200; i++) {
            assertThat(router.shouldUseEs())
                    .as("weight=0 은 항상 inline")
                    .isFalse();
        }
    }

    @Test
    void weight_100_alwaysRoutesToEs() {
        CanaryRouter router = router(100);
        for (int i = 0; i < 200; i++) {
            assertThat(router.shouldUseEs())
                    .as("weight=100 은 항상 ES")
                    .isTrue();
        }
    }

    @Test
    void weight_50_approximatelyHalfToEs() {
        CanaryRouter router = router(50);
        int esCount = 0;
        int samples = 2000;
        for (int i = 0; i < samples; i++) {
            if (router.shouldUseEs()) esCount++;
        }
        // 50% ± 10% 허용 (이항분포 99.9% 신뢰구간)
        assertThat(esCount)
                .as("weight=50: ES 라우팅 비율이 40~60% 이어야 함 (actual=%d/%d)", esCount, samples)
                .isBetween(800, 1200);
    }

    @Test
    void weight_5_fivePercentToEs() {
        CanaryRouter router = router(5);
        int esCount = 0;
        int samples = 10_000;
        for (int i = 0; i < samples; i++) {
            if (router.shouldUseEs()) esCount++;
        }
        // 5% ± 2% 허용
        assertThat(esCount)
                .as("weight=5: ES 라우팅 비율이 3~7%% 이어야 함 (actual=%d/%d)", esCount, samples)
                .isBetween(300, 700);
    }

    @Test
    void weight_negative_throwsOnConstruction() {
        assertThatThrownBy(() -> new CanaryProperties(true, -1, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0~100");
    }

    @Test
    void weight_over100_throwsOnConstruction() {
        assertThatThrownBy(() -> new CanaryProperties(true, 101, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0~100");
    }

    @Test
    void canaryRouted_metric_incremented() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetricsRecorder recorder = new AgentMetricsRecorder(registry);
        CanaryRouter router = new CanaryRouter(new CanaryProperties(true, 100, 100), recorder);

        router.shouldUseEs();
        router.shouldUseEs();

        double esCount = registry.counter("ai.canary.routed.total", "backend", "es").count();
        assertThat(esCount).isEqualTo(2.0);
    }

    // ─────────────────────────────────────────────────────────────────────

    private CanaryRouter router(int weight) {
        return new CanaryRouter(new CanaryProperties(true, weight, 100), metricsRecorder);
    }
}
