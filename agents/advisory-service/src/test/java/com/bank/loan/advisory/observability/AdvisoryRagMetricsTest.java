package com.bank.loan.advisory.observability;

import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AdvisoryMetrics RAG 검색·임베딩 메트릭 단위 테스트.
 * SimpleMeterRegistry 로 실제 메트릭 기록 여부를 검증한다.
 */
class AdvisoryRagMetricsTest {

    SimpleMeterRegistry meterRegistry;
    AdvisoryMetrics     metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        ReviewAdvisoryReportRepository mockRepo = mock(ReviewAdvisoryReportRepository.class);
        when(mockRepo.countOpenBySeverity(anyString())).thenReturn(0L);
        metrics = new AdvisoryMetrics(meterRegistry, mockRepo);
    }

    // ── 검색 타이머 ────────────────────────────────────────────────────────────

    @Test
    void 검색_성공_타이머_count_1_증가() {
        Timer.Sample sample = metrics.startRagSearchTimer();
        metrics.recordRagSearchDuration(sample, "POLICY_CITATION", "success");

        Timer timer = meterRegistry.find(AdvisoryMetrics.M_RAG_SEARCH_DURATION)
                .tag("kind", "POLICY_CITATION").tag("status", "success").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
    }

    @Test
    void 검색_오류_타이머_kind_status_태그_분리() {
        Timer.Sample s1 = metrics.startRagSearchTimer();
        metrics.recordRagSearchDuration(s1, "SIMILAR_CASE", "success");

        Timer.Sample s2 = metrics.startRagSearchTimer();
        metrics.recordRagSearchDuration(s2, "SIMILAR_CASE", "error");

        Timer success = meterRegistry.find(AdvisoryMetrics.M_RAG_SEARCH_DURATION)
                .tag("kind", "SIMILAR_CASE").tag("status", "success").timer();
        Timer error   = meterRegistry.find(AdvisoryMetrics.M_RAG_SEARCH_DURATION)
                .tag("kind", "SIMILAR_CASE").tag("status", "error").timer();

        assertThat(success).isNotNull();
        assertThat(error).isNotNull();
        assertThat(success.count()).isEqualTo(1L);
        assertThat(error.count()).isEqualTo(1L);
    }

    // ── 검색 결과 건수 분포 ─────────────────────────────────────────────────────

    @Test
    void 검색_결과_건수_summary_기록() {
        metrics.recordRagSearchResults(3, "POLICY_CITATION");
        metrics.recordRagSearchResults(5, "POLICY_CITATION");

        DistributionSummary summary = meterRegistry.find(AdvisoryMetrics.M_RAG_SEARCH_RESULTS)
                .tag("kind", "POLICY_CITATION").summary();
        assertThat(summary).isNotNull();
        assertThat(summary.count()).isEqualTo(2L);
        assertThat(summary.totalAmount()).isEqualTo(8.0);
    }

    @Test
    void 검색_결과_0건도_정상_기록() {
        metrics.recordRagSearchResults(0, "SIMILAR_CASE");

        DistributionSummary summary = meterRegistry.find(AdvisoryMetrics.M_RAG_SEARCH_RESULTS)
                .tag("kind", "SIMILAR_CASE").summary();
        assertThat(summary).isNotNull();
        assertThat(summary.count()).isEqualTo(1L);
        assertThat(summary.totalAmount()).isEqualTo(0.0);
    }

    // ── 임베딩 타이머 + 카운터 ──────────────────────────────────────────────────

    @Test
    void 임베딩_성공_타이머_및_카운터_동시_기록() {
        Timer.Sample sample = metrics.startRagEmbeddingTimer();
        metrics.recordRagEmbeddingDuration(sample, "OPENAI_3S", "success");

        Timer timer = meterRegistry.find(AdvisoryMetrics.M_RAG_EMBEDDING_DURATION)
                .tag("model", "OPENAI_3S").tag("status", "success").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);

        double callCount = meterRegistry.find(AdvisoryMetrics.M_RAG_EMBEDDING_CALLS)
                .tag("model", "OPENAI_3S").tag("status", "success").counter().count();
        assertThat(callCount).isEqualTo(1.0);
    }

    @Test
    void 임베딩_오류_status_error_태그_기록() {
        Timer.Sample sample = metrics.startRagEmbeddingTimer();
        metrics.recordRagEmbeddingDuration(sample, "OPENAI_3S", "error");

        double errorCount = meterRegistry.find(AdvisoryMetrics.M_RAG_EMBEDDING_CALLS)
                .tag("model", "OPENAI_3S").tag("status", "error").counter().count();
        assertThat(errorCount).isEqualTo(1.0);
    }

    @Test
    void 임베딩_성공_오류_카운터_독립_집계() {
        metrics.recordRagEmbeddingDuration(metrics.startRagEmbeddingTimer(), "OPENAI_3S", "success");
        metrics.recordRagEmbeddingDuration(metrics.startRagEmbeddingTimer(), "OPENAI_3S", "success");
        metrics.recordRagEmbeddingDuration(metrics.startRagEmbeddingTimer(), "OPENAI_3S", "error");

        double successCount = meterRegistry.find(AdvisoryMetrics.M_RAG_EMBEDDING_CALLS)
                .tag("status", "success").counter().count();
        double errorCount = meterRegistry.find(AdvisoryMetrics.M_RAG_EMBEDDING_CALLS)
                .tag("status", "error").counter().count();

        assertThat(successCount).isEqualTo(2.0);
        assertThat(errorCount).isEqualTo(1.0);
    }

    @Test
    void 임베딩_타이머_percentile_histogram_bucket_발행() {
        // publishPercentileHistogram() 가 있어야 Prometheus 스크레이프에 _bucket 시계열이 노출된다.
        // AdvisoryRagEmbeddingLatencySlow alert 의 histogram_quantile(..._bucket..) 가 이에 의존.
        PrometheusMeterRegistry promRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        ReviewAdvisoryReportRepository mockRepo = mock(ReviewAdvisoryReportRepository.class);
        when(mockRepo.countOpenBySeverity(anyString())).thenReturn(0L);
        AdvisoryMetrics promMetrics = new AdvisoryMetrics(promRegistry, mockRepo);

        promMetrics.recordRagEmbeddingDuration(promMetrics.startRagEmbeddingTimer(), "OPENAI_3S", "success");

        assertThat(promRegistry.scrape())
                .contains("advisory_rag_embedding_duration_seconds_bucket");
    }
}
