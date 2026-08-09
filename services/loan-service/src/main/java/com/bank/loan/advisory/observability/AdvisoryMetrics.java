package com.bank.loan.advisory.observability;

import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 어드바이저리 운영 지표. Prometheus `/actuator/prometheus` 로 노출.
 *
 *   advisory_report_published_total{rule_cd, severity}   counter   리포트 발행 누적
 *   advisory_ack_response_total{response_cd}             counter   ack 응답 누적
 *   advisory_critical_gate_blocked_total                 counter   약정 게이트 차단 누적
 *   advisory_open_reports{severity}                      gauge     미해결(OPEN/VIEWED) 리포트 수
 *   advisory_evaluate_duration_seconds{mode}             timer     룰 평가 지연시간 분포
 *   advisory_rag_backfill_processed_total                counter   백필 처리(신규 적재) 누적
 *   advisory_rag_backfill_skipped_total                  counter   백필 건너뜀(이미 존재) 누적
 *   advisory_rag_backfill_failed_total                   counter   백필 실패 누적
 *   advisory_rag_search_duration_seconds{kind,status}   timer     RAG 코사인 검색 지연시간
 *   advisory_rag_search_results{kind}                   summary   RAG 검색 결과 건수 분포
 *   advisory_rag_embedding_duration_seconds{model,status} timer   임베딩 API 호출 지연시간
 *   advisory_rag_embedding_calls_total{model,status}    counter   임베딩 API 호출 누적
 */
@Component
@RequiredArgsConstructor
public class AdvisoryMetrics {

    public static final String M_PUBLISHED          = "advisory_report_published_total";
    public static final String M_ACK_RESPONSE       = "advisory_ack_response_total";
    public static final String M_GATE_BLOCKED       = "advisory_critical_gate_blocked_total";
    public static final String M_OPEN_REPORTS       = "advisory_open_reports";
    public static final String M_EVALUATE_DURATION  = "advisory_evaluate_duration_seconds";

    public static final String M_BACKFILL_PROCESSED     = "advisory_rag_backfill_processed_total";
    public static final String M_BACKFILL_SKIPPED       = "advisory_rag_backfill_skipped_total";
    public static final String M_BACKFILL_FAILED        = "advisory_rag_backfill_failed_total";

    public static final String M_RAG_SEARCH_DURATION   = "advisory_rag_search_duration_seconds";
    public static final String M_RAG_SEARCH_RESULTS    = "advisory_rag_search_results";
    public static final String M_RAG_EMBEDDING_DURATION = "advisory_rag_embedding_duration_seconds";
    public static final String M_RAG_EMBEDDING_CALLS   = "advisory_rag_embedding_calls_total";

    private final MeterRegistry meterRegistry;
    private final ReviewAdvisoryReportRepository reportRepo;

    @PostConstruct
    void registerGauges() {
        for (String severity : List.of(
                ReviewAdvisoryReport.SEVERITY_INFO,
                ReviewAdvisoryReport.SEVERITY_WARN,
                ReviewAdvisoryReport.SEVERITY_CRITICAL)) {
            Gauge.builder(M_OPEN_REPORTS, reportRepo, r -> r.countOpenBySeverity(severity))
                    .tag("severity", severity)
                    .description("미해결(OPEN/VIEWED) 어드바이저리 리포트 수")
                    .register(meterRegistry);
        }
    }

    public void incrementPublished(String ruleCd, String severityCd) {
        meterRegistry.counter(M_PUBLISHED, "rule_cd", ruleCd, "severity", severityCd).increment();
    }

    public void incrementAckResponse(String responseCd) {
        meterRegistry.counter(M_ACK_RESPONSE, "response_cd", responseCd).increment();
    }

    public void incrementGateBlocked() {
        meterRegistry.counter(M_GATE_BLOCKED).increment();
    }

    public Timer.Sample startEvaluateTimer() {
        return Timer.start(meterRegistry);
    }

    public void recordEvaluateDuration(Timer.Sample sample, String mode) {
        sample.stop(Timer.builder(M_EVALUATE_DURATION)
                .tag("mode", mode)
                .description("어드바이저리 룰 평가 지연시간")
                .register(meterRegistry));
    }

    public void incrementBackfillProcessed() {
        meterRegistry.counter(M_BACKFILL_PROCESSED).increment();
    }

    public void incrementBackfillSkipped() {
        meterRegistry.counter(M_BACKFILL_SKIPPED).increment();
    }

    public void incrementBackfillFailed() {
        meterRegistry.counter(M_BACKFILL_FAILED).increment();
    }

    // ── RAG 검색 메트릭 ──────────────────────────────────────────────────────

    public Timer.Sample startRagSearchTimer() {
        return Timer.start(meterRegistry);
    }

    public void recordRagSearchDuration(Timer.Sample sample, String kind, String status) {
        sample.stop(Timer.builder(M_RAG_SEARCH_DURATION)
                .tag("kind", kind)
                .tag("status", status)
                .description("Advisory RAG 코사인 검색 지연시간")
                .register(meterRegistry));
    }

    public void recordRagSearchResults(int count, String kind) {
        DistributionSummary.builder(M_RAG_SEARCH_RESULTS)
                .tag("kind", kind)
                .description("Advisory RAG 검색 결과 건수 분포")
                .register(meterRegistry)
                .record(count);
    }

    // ── RAG 임베딩 메트릭 ────────────────────────────────────────────────────

    public Timer.Sample startRagEmbeddingTimer() {
        return Timer.start(meterRegistry);
    }

    public void recordRagEmbeddingDuration(Timer.Sample sample, String model, String status) {
        sample.stop(Timer.builder(M_RAG_EMBEDDING_DURATION)
                .tag("model", model)
                .tag("status", status)
                .publishPercentileHistogram()
                .description("Advisory RAG 임베딩 API 호출 지연시간")
                .register(meterRegistry));
        meterRegistry.counter(M_RAG_EMBEDDING_CALLS, "model", model, "status", status).increment();
    }
}
