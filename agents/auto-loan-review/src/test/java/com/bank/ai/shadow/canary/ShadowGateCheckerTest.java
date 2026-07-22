package com.bank.ai.shadow.canary;

import com.bank.ai.shadow.ShadowResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ShadowGateChecker 단위 테스트 — E4-4.
 *
 * <p>케이스:
 * <ul>
 *   <li>passed — agreement ≥ 0.95 ∧ citationMiss ≤ 0.05</li>
 *   <li>fail_agreement — agreement < 0.95</li>
 *   <li>fail_citation  — citationMiss > 0.05</li>
 *   <li>fail_both      — 두 지표 모두 미달</li>
 *   <li>insufficient   — total < minShadowRuns</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ShadowGateCheckerTest {

    @Mock
    private ShadowResultRepository repository;

    private ShadowGateChecker checker;

    private static final LocalDate FROM = LocalDate.of(2026, 5, 25);
    private static final LocalDate TO   = LocalDate.of(2026, 6, 1);

    @BeforeEach
    void setUp() {
        // minShadowRuns=100
        CanaryProperties props = new CanaryProperties(true, 5, 100);
        checker = new ShadowGateChecker(repository, props);
    }

    @Test
    void passed_whenBothMetricsMeetThreshold() {
        givenMetrics(200, 0.97, 0.02);

        GateStatus status = checker.check(FROM, TO);

        assertThat(status.passed()).isTrue();
        assertThat(status.total()).isEqualTo(200);
        assertThat(status.agreementRate()).isEqualTo(0.97);
        assertThat(status.citationMissRate()).isEqualTo(0.02);
        assertThat(status.failReason()).isNull();
    }

    @Test
    void fail_whenAgreementRateBelowThreshold() {
        givenMetrics(150, 0.93, 0.03);

        GateStatus status = checker.check(FROM, TO);

        assertThat(status.passed()).isFalse();
        assertThat(status.failReason()).contains("agreement=0.930");
        assertThat(status.failReason()).doesNotContain("citation_miss");
    }

    @Test
    void fail_whenCitationMissRateAboveThreshold() {
        givenMetrics(150, 0.96, 0.07);

        GateStatus status = checker.check(FROM, TO);

        assertThat(status.passed()).isFalse();
        assertThat(status.failReason()).contains("citation_miss=0.070");
        assertThat(status.failReason()).doesNotContain("agreement");
    }

    @Test
    void fail_whenBothMetricsMiss() {
        givenMetrics(150, 0.90, 0.08);

        GateStatus status = checker.check(FROM, TO);

        assertThat(status.passed()).isFalse();
        assertThat(status.failReason()).contains("agreement=0.900");
        assertThat(status.failReason()).contains("citation_miss=0.080");
    }

    @Test
    void insufficientData_whenTotalBelowMinimum() {
        when(repository.totalCount(any(), any())).thenReturn(50);

        GateStatus status = checker.check(FROM, TO);

        assertThat(status.passed()).isFalse();
        assertThat(status.failReason()).contains("INSUFFICIENT_DATA");
        assertThat(status.failReason()).contains("50");
        assertThat(status.failReason()).contains("100");
        assertThat(status.total()).isEqualTo(50);
    }

    @Test
    void boundaryCase_exactlyAtThreshold_passes() {
        // 경계값: agreement = 0.95, citationMiss = 0.05 → pass
        givenMetrics(100, 0.95, 0.05);

        GateStatus status = checker.check(FROM, TO);

        assertThat(status.passed()).isTrue();
    }

    // ─────────────────────────────────────────────────────────────────────

    private void givenMetrics(int total, double agreementRate, double citationMissRate) {
        when(repository.totalCount(any(), any())).thenReturn(total);
        when(repository.agreementRate(any(), any())).thenReturn(agreementRate);
        when(repository.citationMissRate(any(), any())).thenReturn(citationMissRate);
    }
}
