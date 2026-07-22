package com.bank.ai.drift;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class DriftAlertService {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, AtomicReference<Double>> psiGauges = new ConcurrentHashMap<>();

    /** flagged 공정성 리포트 → 카운터 + WARN 로그. */
    public void alertFairness(FairnessReport report) {
        Counter.builder("ai.fairness.flagged.total")
            .tag("group", report.groupKey())
            .register(registry)
            .increment();
        log.warn("[Fairness] 임계치 초과 group={} approvalRate={} gap={}",
            report.groupKey(), report.approvalRate(), report.rateGap());
    }

    /** CRITICAL PSI → 메트릭 카운터 + WARN 로그. PSI 값은 feature별 gauge에 항상 갱신. */
    public void alert(PsiDriftReport report) {
        if (report.status() == PsiStatus.CRITICAL) {
            Counter.builder("ai.drift.psi.critical.total")
                .tag("feature", report.featureName())
                .register(registry)
                .increment();
            log.warn("[Drift] PSI CRITICAL feature={} psi={}", report.featureName(), report.psiValue());
        }
        AtomicReference<Double> holder = psiGauges.computeIfAbsent(report.featureName(), feature -> {
            AtomicReference<Double> ref = new AtomicReference<>(Double.NaN);
            Gauge.builder("ai.drift.psi.value", ref, AtomicReference::get)
                .tag("feature", feature)
                .register(registry);
            return ref;
        });
        holder.set(report.psiValue());
    }
}
