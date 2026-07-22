package com.bank.ai.drift;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DriftAlertService 순수 단위 테스트 — B4 PSI Drift Alert.
 */
class DriftAlertServiceTest {

    private MeterRegistry registry;
    private DriftAlertService alertService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        alertService = new DriftAlertService(registry);
    }

    // ── TC1: CRITICAL 보고서 → ai.drift.psi.critical.total +1 ────────────
    @Test
    void alert_criticalReport_incrementsCounter() {
        PsiDriftReport report = new PsiDriftReport("creditScore", 0.25, PsiStatus.CRITICAL, 100, "v1");

        alertService.alert(report);

        Counter counter = registry.find("ai.drift.psi.critical.total")
            .tag("feature", "creditScore")
            .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ── TC2: STABLE 보고서 → 카운터 없음 ─────────────────────────────────
    @Test
    void alert_stableReport_noCounter() {
        PsiDriftReport report = new PsiDriftReport("creditScore", 0.05, PsiStatus.STABLE, 100, "v1");

        alertService.alert(report);

        Counter counter = registry.find("ai.drift.psi.critical.total")
            .tag("feature", "creditScore")
            .counter();
        assertThat(counter).isNull();
    }

    // ── TC3: flagged 공정성 리포트 → ai.fairness.flagged.total 카운터 +1 ──
    @Test
    void alertFairness_flaggedReport_incrementsCounter() {
        FairnessReport report = new FairnessReport(
            java.time.LocalDate.of(2041, 1, 1), "age_band:60plus",
            0.2, 10, 0.6, -0.4, true);

        alertService.alertFairness(report);

        Counter counter = registry.find("ai.fairness.flagged.total")
            .tag("group", "age_band:60plus")
            .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ── TC5: 2회 alert → gauge가 최신값을 반영하고 NaN이 아님 ─────────────
    @Test
    void alert_twiceSameFeature_gaugeReflectsLatestValue() {
        PsiDriftReport first  = new PsiDriftReport("creditScore", 0.05, PsiStatus.STABLE,   100, "v1");
        PsiDriftReport second = new PsiDriftReport("creditScore", 0.22, PsiStatus.CRITICAL, 120, "v1");

        alertService.alert(first);
        alertService.alert(second);

        Gauge gauge = registry.find("ai.drift.psi.value")
            .tag("feature", "creditScore")
            .gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isNotNaN();
        assertThat(gauge.value()).isEqualTo(0.22);
    }
}
