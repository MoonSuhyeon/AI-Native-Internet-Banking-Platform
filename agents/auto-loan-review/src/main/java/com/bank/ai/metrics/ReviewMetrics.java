package com.bank.ai.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * ML 대출 심사 결과 메트릭 — 결정 분포, 처리 시간, 입력값 분포, 드리프트 감지.
 *
 * <p>메트릭 목록:
 * <ul>
 *   <li>{@code review.decision.total}          Counter             decision, modelVersion</li>
 *   <li>{@code review.duration.seconds}         Timer(histogram)    modelVersion</li>
 *   <li>{@code review.score}                    DistributionSummary decision</li>
 *   <li>{@code review.input.credit.score}       DistributionSummary —</li>
 *   <li>{@code review.input.dsr}                DistributionSummary —</li>
 *   <li>{@code review.input.missing.total}      Counter             field</li>
 *   <li>{@code review.input.outlier.total}      Counter             field</li>
 *   <li>{@code review.inference.error.total}    Counter             —</li>
 * </ul>
 */
@Component
public class ReviewMetrics {

    private final MeterRegistry registry;

    public ReviewMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 심사 결과(결정 + 모델 버전) 카운터. decision = APPROVE | REJECT | CONDITIONAL */
    public void recordDecision(String decision, String modelVersion) {
        Counter.builder("review.decision.total")
                .tag("decision", decision)
                .tag("modelVersion", modelVersion != null ? modelVersion : "unknown")
                .register(registry)
                .increment();
    }

    /** 심사 end-to-end 처리 시간 히스토그램 (p50/p95/p99용). */
    public void recordDuration(Duration duration, String modelVersion) {
        Timer.builder("review.duration.seconds")
                .tag("modelVersion", modelVersion != null ? modelVersion : "unknown")
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    /** 모델 신뢰도 점수 분포 (결정별). 드리프트 감지 기준으로 활용. */
    public void recordScore(double score, String decision) {
        DistributionSummary.builder("review.score")
                .tag("decision", decision)
                .publishPercentileHistogram()
                .minimumExpectedValue(1e-9)
                .maximumExpectedValue(1.0)
                .register(registry)
                .record(score);
    }

    /** 입력 신용점수 분포 (0~1000). 분포 이동 시 데이터 드리프트 신호. */
    public void recordCreditScore(int creditScore) {
        DistributionSummary.builder("review.input.credit.score")
                .publishPercentileHistogram()
                .minimumExpectedValue(1.0)
                .maximumExpectedValue(1000.0)
                .register(registry)
                .record(creditScore);
    }

    /** 입력 DSR 분포 (0.0~1.5). 분포 이동 시 데이터 드리프트 신호. */
    public void recordDsr(double dsr) {
        DistributionSummary.builder("review.input.dsr")
                .publishPercentileHistogram()
                .minimumExpectedValue(1e-9)
                .maximumExpectedValue(1.5)
                .register(registry)
                .record(dsr);
    }

    /** 입력 결측값 감지 (field = creditScoreProxy | dsr 등). */
    public void recordMissing(String field) {
        Counter.builder("review.input.missing.total")
                .tag("field", field)
                .register(registry)
                .increment();
    }

    /** 입력 이상값 감지 (정상 범위 이탈 필드). */
    public void recordOutlier(String field) {
        Counter.builder("review.input.outlier.total")
                .tag("field", field)
                .register(registry)
                .increment();
    }

    /** 추론 서버 오류 (INFERENCE_FAILED 예외 발생 시). */
    public void recordInferenceError() {
        Counter.builder("review.inference.error.total")
                .register(registry)
                .increment();
    }

}
