package com.bank.ai.metrics;

import com.bank.ai.agent.FallbackReason;
import com.bank.ai.rule.domain.HardFailReason;
import com.bank.ai.rule.domain.Track;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentMetricsRecorder 단위 테스트 — phase-b-operational.md §B2.
 *
 * <p>SimpleMeterRegistry 로 실제 Micrometer 동작 검증.
 * 스프링 컨텍스트 없이 순수 단위 테스트.
 */
class AgentMetricsRecorderTest {

    private MeterRegistry registry;
    private AgentMetricsRecorder recorder;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        recorder = new AgentMetricsRecorder(registry);
    }

    // ── TC 1: recordRun → 카운터 + 타이머 증가 ────────────────────────────

    @Test
    void recordRun_incrementsCounter() {
        recorder.recordRun(Track.TRACK_3, AgentOutcome.SUCCESS, Duration.ofMillis(500));

        Counter counter = registry.find("ai.agent.runs.total")
                .tag(AgentMetricsTags.TRACK, "TRACK_3")
                .tag(AgentMetricsTags.OUTCOME, "SUCCESS")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordRun_timerRecorded() {
        recorder.recordRun(Track.TRACK_1, AgentOutcome.FALLBACK, Duration.ofSeconds(2));

        var timer = registry.find("ai.agent.latency.seconds")
                .tag(AgentMetricsTags.TRACK, "TRACK_1")
                .tag(AgentMetricsTags.OUTCOME, "FALLBACK")
                .timer();

        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThan(0);
    }

    // ── TC 2: recordFallback → reason 태그 ───────────────────────────────

    @Test
    void recordFallback_taggedByReason() {
        recorder.recordFallback(FallbackReason.LLM_RATE_LIMITED);

        Counter counter = registry.find("ai.agent.fallback.total")
                .tag(AgentMetricsTags.REASON, "LLM_RATE_LIMITED")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ── TC 3: recordLlmCall → 토큰 카운터 포함 ───────────────────────────

    @Test
    void recordLlmCall_incrementsAllCounters() {
        var cost = new LlmCostSummary(100, 50, 0.001);
        recorder.recordLlmCall("stub-v1", AgentOutcome.SUCCESS, Duration.ofMillis(300), cost);

        assertThat(registry.find("ai.agent.llm.calls.total")
                .tag(AgentMetricsTags.MODEL, "stub-v1").counter()).isNotNull();

        assertThat(registry.find("ai.agent.tokens.input.total")
                .tag(AgentMetricsTags.MODEL, "stub-v1").counter().count()).isEqualTo(100.0);

        assertThat(registry.find("ai.agent.tokens.output.total")
                .tag(AgentMetricsTags.MODEL, "stub-v1").counter().count()).isEqualTo(50.0);
    }

    @Test
    void recordLlmCall_zeroTokens_noTokenCounters() {
        recorder.recordLlmCall("stub-v1", AgentOutcome.FALLBACK, Duration.ZERO, LlmCostSummary.ZERO);

        // ZERO 비용이면 토큰/비용 카운터 미생성
        assertThat(registry.find("ai.agent.tokens.input.total").counter()).isNull();
        assertThat(registry.find("ai.agent.tokens.output.total").counter()).isNull();
        assertThat(registry.find("ai.agent.cost.usd.total").counter()).isNull();
    }

    // ── TC 4: recordAuditLogSize → DistributionSummary ───────────────────

    @Test
    void recordAuditLogSize_distributionSummaryRecorded() {
        recorder.recordAuditLogSize(1024);
        recorder.recordAuditLogSize(2048);

        var summary = registry.find("ai.audit.log.size.bytes").summary();
        assertThat(summary).isNotNull();
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.mean()).isEqualTo(1536.0);
    }

    // ── TC 5: recordDisagreement + recordHardFail ─────────────────────────

    @Test
    void recordDisagreement_trackTagged() {
        recorder.recordDisagreement(Track.TRACK_3);

        Counter counter = registry.find("ai.agent.disagreement.total")
                .tag(AgentMetricsTags.TRACK, "TRACK_3").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordHardFail_reasonTagged() {
        recorder.recordHardFail(HardFailReason.DSR_EXCEEDED);

        Counter counter = registry.find("ai.agent.hard.fail.total")
                .tag(AgentMetricsTags.REASON, "DSR_EXCEEDED").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ── TC 6: recordPerRunGuardCounts → DistributionSummary ─────────────────

    @Test
    void recordPerRunGuardCounts_success_summaryRecorded() {
        recorder.recordPerRunGuardCounts(Track.TRACK_3, AgentOutcome.SUCCESS, 4, 1);
        recorder.recordPerRunGuardCounts(Track.TRACK_3, AgentOutcome.SUCCESS, 6, 2);

        var toolSummary = registry.find("ai.agent.tool.calls.per_run")
                .tag(AgentMetricsTags.TRACK, "TRACK_3")
                .tag(AgentMetricsTags.OUTCOME, "SUCCESS")
                .summary();
        assertThat(toolSummary).isNotNull();
        assertThat(toolSummary.count()).isEqualTo(2);
        assertThat(toolSummary.mean()).isEqualTo(5.0);

        var llmSummary = registry.find("ai.agent.llm.calls.per_run")
                .tag(AgentMetricsTags.TRACK, "TRACK_3")
                .tag(AgentMetricsTags.OUTCOME, "SUCCESS")
                .summary();
        assertThat(llmSummary).isNotNull();
        assertThat(llmSummary.count()).isEqualTo(2);
        assertThat(llmSummary.mean()).isEqualTo(1.5);
    }

    @Test
    void recordPerRunGuardCounts_fallback_separateFromSuccess() {
        recorder.recordPerRunGuardCounts(Track.TRACK_3, AgentOutcome.SUCCESS, 5, 2);
        recorder.recordPerRunGuardCounts(Track.TRACK_3, AgentOutcome.FALLBACK, 2, 0);

        // SUCCESS 버킷은 count=1
        var successSummary = registry.find("ai.agent.tool.calls.per_run")
                .tag(AgentMetricsTags.TRACK, "TRACK_3")
                .tag(AgentMetricsTags.OUTCOME, "SUCCESS")
                .summary();
        assertThat(successSummary).isNotNull();
        assertThat(successSummary.count()).isEqualTo(1);

        // FALLBACK 버킷도 독립 집계
        var fallbackSummary = registry.find("ai.agent.tool.calls.per_run")
                .tag(AgentMetricsTags.TRACK, "TRACK_3")
                .tag(AgentMetricsTags.OUTCOME, "FALLBACK")
                .summary();
        assertThat(fallbackSummary).isNotNull();
        assertThat(fallbackSummary.count()).isEqualTo(1);
        assertThat(fallbackSummary.mean()).isEqualTo(2.0);
    }

    // ── TC 7: RAG 메트릭 5종 — E4-1 ─────────────────────────────────────────

    @Test
    void recordRagSearchLatency_phase_rrf_timerRecorded() {
        recorder.recordRagSearchLatency("similar_cases", "rrf", Duration.ofMillis(80));

        var timer = registry.find("rag.search.latency.seconds")
                .tag(AgentMetricsTags.CORPUS, "similar_cases")
                .tag(AgentMetricsTags.PHASE, "rrf")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void recordRagSearchLatency_no_phase_defaults_to_all() {
        recorder.recordRagSearchLatency("policy_regulation", Duration.ofMillis(120));

        var timer = registry.find("rag.search.latency.seconds")
                .tag(AgentMetricsTags.CORPUS, "policy_regulation")
                .tag(AgentMetricsTags.PHASE, "all")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void recordRagIndexLag_timerRecorded() {
        recorder.recordRagIndexLag("similar_cases", Duration.ofSeconds(45));

        var timer = registry.find("rag.index.lag.seconds")
                .tag(AgentMetricsTags.CORPUS, "similar_cases")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void recordRagSearchMiss_counterIncremented() {
        recorder.recordRagSearchMiss("similar_cases");
        recorder.recordRagSearchMiss("similar_cases");

        Counter counter = registry.find("rag.search.miss.total")
                .tag(AgentMetricsTags.CORPUS, "similar_cases").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    void recordRagChunkCount_distributionSummaryRecorded() {
        recorder.recordRagChunkCount("policy_regulation", 5);
        recorder.recordRagChunkCount("policy_regulation", 3);

        var summary = registry.find("rag.chunk.count")
                .tag(AgentMetricsTags.CORPUS, "policy_regulation").summary();
        assertThat(summary).isNotNull();
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.mean()).isEqualTo(4.0);
    }

    @Test
    void recordRagCitationCount_distributionSummaryRecorded() {
        recorder.recordRagCitationCount(Track.TRACK_2, 2);
        recorder.recordRagCitationCount(Track.TRACK_2, 3);

        var summary = registry.find("rag.citation.count.per.report")
                .tag(AgentMetricsTags.TRACK, "TRACK_2").summary();
        assertThat(summary).isNotNull();
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.mean()).isEqualTo(2.5);
    }
}
