package com.bank.ai.drift;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class PsiDriftDetector {

    private final DriftProperties props;

    /**
     * PSI = Σ (cur - base) × ln(cur / base)
     * 0 또는 1인 버킷은 epsilon(0.0001)으로 대체.
     */
    public double calculatePsi(List<Double> baselineRatios, List<Double> currentRatios) {
        double psi = 0.0;
        for (int i = 0; i < baselineRatios.size(); i++) {
            double base = Math.max(baselineRatios.get(i), 0.0001);
            double cur  = Math.max(currentRatios.get(i),  0.0001);
            psi += (cur - base) * Math.log(cur / base);
        }
        return psi;
    }

    public PsiStatus classify(double psi) {
        if (psi < props.psiWarningThreshold()) return PsiStatus.STABLE;
        if (psi < props.psiCriticalThreshold()) return PsiStatus.WARNING;
        return PsiStatus.CRITICAL;
    }

    /**
     * values를 bucketBounds(각 원소는 [low,high])에 따라 버킷화하여 비율 반환.
     * bucketBounds.size() == N → 결과 리스트 크기 N.
     */
    public List<Double> bucketize(List<Double> values, List<double[]> bucketBounds) {
        int[] counts = new int[bucketBounds.size()];
        int total = values.size();
        for (double v : values) {
            for (int i = 0; i < bucketBounds.size(); i++) {
                double[] b = bucketBounds.get(i);
                if (v >= b[0] && v < b[1]) {
                    counts[i]++;
                    break;
                }
            }
        }
        List<Double> ratios = new ArrayList<>();
        for (int c : counts) ratios.add(total > 0 ? (double) c / total : 0.0);
        return ratios;
    }
}
