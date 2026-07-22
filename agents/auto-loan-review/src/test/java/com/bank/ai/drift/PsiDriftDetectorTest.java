package com.bank.ai.drift;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PsiDriftDetector 순수 단위 테스트 — B4 PSI Drift.
 */
@ExtendWith(MockitoExtension.class)
class PsiDriftDetectorTest {

    private PsiDriftDetector detector;

    @BeforeEach
    void setUp() {
        DriftProperties props = new DriftProperties(true, "v1", 0.10, 0.20, 0.05, 6, "0 0 2 * * MON", List.of("creditScore"));
        detector = new PsiDriftDetector(props);
    }

    // ── TC1: 동일 분포 → PSI ≈ 0, classify=STABLE ─────────────────────────
    @Test
    void calculatePsi_identicalDistributions_returnsNearZero() {
        List<Double> baseline = List.of(0.2, 0.3, 0.3, 0.2);
        List<Double> current  = List.of(0.2, 0.3, 0.3, 0.2);

        double psi = detector.calculatePsi(baseline, current);

        assertThat(psi).isLessThan(0.001);
        assertThat(detector.classify(psi)).isEqualTo(PsiStatus.STABLE);
    }

    // ── TC2: 극단 이동 → PSI ≥ 0.20, classify=CRITICAL ──────────────────
    @Test
    void calculatePsi_extremeShift_returnsCritical() {
        List<Double> baseline = List.of(0.7, 0.1, 0.1, 0.1);
        List<Double> current  = List.of(0.1, 0.1, 0.1, 0.7);

        double psi = detector.calculatePsi(baseline, current);

        assertThat(psi).isGreaterThanOrEqualTo(0.20);
        assertThat(detector.classify(psi)).isEqualTo(PsiStatus.CRITICAL);
    }

    // ── TC3: PSI=0.15 → classify=WARNING ─────────────────────────────────
    @Test
    void classify_psiInWarningRange_returnsWarning() {
        assertThat(detector.classify(0.15)).isEqualTo(PsiStatus.WARNING);
    }

    // ── TC4: bucketize — 올바른 버킷 할당 ────────────────────────────────
    @Test
    void bucketize_assignsValuesToCorrectBuckets() {
        List<Double> values = List.of(150.0, 250.0, 350.0, 450.0);
        List<double[]> bounds = List.of(
            new double[]{100.0, 200.0},
            new double[]{200.0, 300.0},
            new double[]{300.0, 400.0},
            new double[]{400.0, 500.0}
        );

        List<Double> ratios = detector.bucketize(values, bounds);

        assertThat(ratios).hasSize(4);
        assertThat(ratios).allMatch(r -> r == 0.25);
    }
}
