package com.bank.aigateway.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GatewayMetrics {

    private final MeterRegistry registry;

    public Timer.Sample startAnalysisTimer() {
        return Timer.start(registry);
    }

    public void recordAnalysisDuration(Timer.Sample sample, String analysisType) {
        sample.stop(Timer.builder("aigateway.analysis.duration")
                .tag("type", analysisType)
                .publishPercentileHistogram()
                .register(registry));
    }

    public void recordTokens(String analysisType, int inputTokens, int outputTokens) {
        Counter.builder("aigateway.tokens.input")
                .tag("type", analysisType)
                .register(registry)
                .increment(inputTokens);
        Counter.builder("aigateway.tokens.output")
                .tag("type", analysisType)
                .register(registry)
                .increment(outputTokens);
    }

    /** 분석 결과 건수 (conclusion 태그: BIAS_SUSPECTED, NO_BIAS_DETECTED, VIOLATION_SUSPECTED, COMPLIANT, INSUFFICIENT_DATA) */
    public void recordAnalysisResult(String analysisType, String conclusion) {
        Counter.builder("aigateway.analysis.result.total")
                .tag("type", analysisType)
                .tag("conclusion", conclusion)
                .register(registry)
                .increment();
    }

    /** 최대 턴 초과로 INSUFFICIENT_DATA 폴백된 건수 */
    public void recordLoopTimeout(String analysisType) {
        Counter.builder("aigateway.loop.timeout.total")
                .tag("type", analysisType)
                .register(registry)
                .increment();
    }

    /**
     * run 1회당 턴 수 분포 기록 (type, outcome 태그).
     *
     * <p>outcome="completed" 필터 시 정상 종료 run의 턴 분포를 구할 수 있어
     * MAX_TURNS 상한(aigateway.agent.max-turns) 근거를 p95/p99로 산출한다.
     */
    public void recordLoopTurns(String analysisType, int turns, boolean timedOut) {
        String outcome = timedOut ? "timeout" : "completed";
        DistributionSummary.builder("aigateway.loop.turns.per_run")
                .tag("type", analysisType)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .minimumExpectedValue(1.0)
                .maximumExpectedValue(8.0)
                .register(registry)
                .record(turns);
    }
}
