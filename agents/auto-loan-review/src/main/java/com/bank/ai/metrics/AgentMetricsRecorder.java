package com.bank.ai.metrics;

import com.bank.ai.agent.FallbackReason;
import com.bank.ai.rule.domain.HardFailReason;
import com.bank.ai.rule.domain.Track;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * AI 파이프라인 Micrometer 메트릭 중앙 기록기 — phase-b-operational.md §B2.
 *
 * <p>메트릭 목록 (20종):
 * <ul>
 *   <li>{@code ai.agent.runs.total}              Counter             track, outcome</li>
 *   <li>{@code ai.agent.latency.seconds}          Timer               track, outcome</li>
 *   <li>{@code ai.agent.tool.calls.total}         Counter             tool_name, status</li>
 *   <li>{@code ai.agent.tool.calls.per_run}       DistributionSummary track, outcome</li>
 *   <li>{@code ai.agent.llm.calls.total}          Counter             model, outcome</li>
 *   <li>{@code ai.agent.llm.calls.per_run}        DistributionSummary track, outcome</li>
 *   <li>{@code ai.agent.llm.latency.seconds}      Timer               model</li>
 *   <li>{@code ai.agent.tokens.input.total}       Counter             model</li>
 *   <li>{@code ai.agent.tokens.output.total}      Counter             model</li>
 *   <li>{@code ai.agent.cost.usd.total}           Counter             model</li>
 *   <li>{@code ai.agent.rpm.remaining}            Gauge               — (LlmRequestRateMeter 등록)</li>
 *   <li>{@code ai.agent.rpd.remaining}            Gauge               — (LlmRequestRateMeter 등록)</li>
 *   <li>{@code ai.agent.disagreement.total}       Counter             track</li>
 *   <li>{@code ai.agent.fallback.total}           Counter             reason</li>
 *   <li>{@code ai.agent.hard.fail.total}          Counter             reason</li>
 *   <li>{@code ai.audit.log.size.bytes}           DistributionSummary —</li>
 *   <li>{@code rag.search.latency.seconds}        Timer               corpus</li>
 *   <li>{@code rag.search.miss.total}             Counter             corpus</li>
 *   <li>{@code rag.chunk.count}                   DistributionSummary corpus</li>
 *   <li>{@code rag.citation.count.per.report}     DistributionSummary track</li>
 * </ul>
 *
 * <p>rpm/rpd Gauge 는 {@code LlmRequestRateMeter#registerGauges()} 에서 이미 등록 — 여기선 제외.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentMetricsRecorder {

    private final MeterRegistry registry;

    // ── 에이전트 실행 ────────────────────────────────────────────────────────

    /**
     * 에이전트 run 완료 기록 (카운터 + 타이머).
     *
     * @param track    트랙 분기 결과
     * @param outcome  SUCCESS / FALLBACK / ERROR
     * @param duration 전체 소요 시간
     */
    public void recordRun(Track track, AgentOutcome outcome, Duration duration) {
        Counter.builder("ai.agent.runs.total")
                .tag(AgentMetricsTags.TRACK, track.name())
                .tag(AgentMetricsTags.OUTCOME, outcome.name())
                .register(registry)
                .increment();

        Timer.builder("ai.agent.latency.seconds")
                .tag(AgentMetricsTags.TRACK, track.name())
                .tag(AgentMetricsTags.OUTCOME, outcome.name())
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    // ── 도구 호출 ────────────────────────────────────────────────────────────

    /**
     * 에이전트 도구(Tool) 호출 기록.
     *
     * @param toolName 도구 이름 (예: PolicyFlagTool, RecomputeWithTermsTool)
     * @param success  성공 여부
     */
    public void recordToolCall(String toolName, boolean success) {
        Counter.builder("ai.agent.tool.calls.total")
                .tag(AgentMetricsTags.TOOL_NAME, toolName)
                .tag(AgentMetricsTags.STATUS, success ? "OK" : "ERROR")
                .register(registry)
                .increment();
    }

    // ── LLM 호출 ─────────────────────────────────────────────────────────────

    /**
     * LLM 단일 호출 기록 (횟수 + 지연 + 토큰 + 비용).
     *
     * @param model   LLM 모델명 (예: stub-v1, gemini-2.5-flash)
     * @param outcome SUCCESS / FALLBACK / ERROR
     * @param latency 호출 소요 시간
     * @param cost    토큰·비용 집계 ({@link LlmCostSummary#ZERO} 허용)
     */
    public void recordLlmCall(String model, AgentOutcome outcome,
                              Duration latency, LlmCostSummary cost) {
        Counter.builder("ai.agent.llm.calls.total")
                .tag(AgentMetricsTags.MODEL, model)
                .tag(AgentMetricsTags.OUTCOME, outcome.name())
                .register(registry)
                .increment();

        Timer.builder("ai.agent.llm.latency.seconds")
                .tag(AgentMetricsTags.MODEL, model)
                .register(registry)
                .record(latency);

        if (cost.inputTokens() > 0) {
            Counter.builder("ai.agent.tokens.input.total")
                    .tag(AgentMetricsTags.MODEL, model)
                    .register(registry)
                    .increment(cost.inputTokens());
        }
        if (cost.outputTokens() > 0) {
            Counter.builder("ai.agent.tokens.output.total")
                    .tag(AgentMetricsTags.MODEL, model)
                    .register(registry)
                    .increment(cost.outputTokens());
        }
        if (cost.estimatedUsdCost() > 0) {
            Counter.builder("ai.agent.cost.usd.total")
                    .tag(AgentMetricsTags.MODEL, model)
                    .register(registry)
                    .increment(cost.estimatedUsdCost());
        }
    }

    // ── 폴백·불일치·하드페일 ──────────────────────────────────────────────────

    /** 에이전트 폴백 기록 (FallbackReason 별). */
    public void recordFallback(FallbackReason reason) {
        Counter.builder("ai.agent.fallback.total")
                .tag(AgentMetricsTags.REASON, reason.name())
                .register(registry)
                .increment();
    }

    /**
     * run 1회당 도구·LLM 호출 수 분포 기록 (track, outcome 태그).
     *
     * <p>outcome="SUCCESS" 필터 시 "정상 종료 run"의 분포를 구할 수 있어
     * 가드레일 상한(maxToolCalls/maxLlmCalls) 근거를 p95/p99로 산출한다.
     * Track3 전용 — Track1/2는 guard 미사용이라 제외.
     */
    public void recordPerRunGuardCounts(Track track, AgentOutcome outcome,
                                        int toolCallCount, int llmCallCount) {
        DistributionSummary.builder("ai.agent.tool.calls.per_run")
                .tag(AgentMetricsTags.TRACK, track.name())
                .tag(AgentMetricsTags.OUTCOME, outcome.name())
                .publishPercentileHistogram()
                .minimumExpectedValue(1.0)
                .maximumExpectedValue(8.0)
                .register(registry)
                .record(toolCallCount);

        DistributionSummary.builder("ai.agent.llm.calls.per_run")
                .tag(AgentMetricsTags.TRACK, track.name())
                .tag(AgentMetricsTags.OUTCOME, outcome.name())
                .publishPercentileHistogram()
                .minimumExpectedValue(1.0)
                .maximumExpectedValue(4.0)
                .register(registry)
                .record(llmCallCount);
    }

    /** 에이전트 의견과 트랙 분기 불일치 기록. */
    public void recordDisagreement(Track track) {
        Counter.builder("ai.agent.disagreement.total")
                .tag(AgentMetricsTags.TRACK, track.name())
                .register(registry)
                .increment();
    }

    /** RuleEngine 하드 페일 기록 (HardFailReason 별). */
    public void recordHardFail(HardFailReason reason) {
        Counter.builder("ai.agent.hard.fail.total")
                .tag(AgentMetricsTags.REASON, reason.name())
                .register(registry)
                .increment();
    }

    // ── 감사 로그 ─────────────────────────────────────────────────────────────

    /** 감사 로그 단건 크기 (UTF-8 bytes) 기록. */
    public void recordAuditLogSize(int bytes) {
        DistributionSummary.builder("ai.audit.log.size.bytes")
                .baseUnit("bytes")
                .register(registry)
                .record(bytes);
    }

    // ── RAG 검색 ──────────────────────────────────────────────────────────────

    /**
     * RAG 검색 지연 기록 (corpus + phase 별).
     *
     * @param phase 검색 단계 — {@code "bm25" / "knn" / "rrf" / "all"}
     */
    public void recordRagSearchLatency(String corpus, String phase, Duration latency) {
        Timer.builder("rag.search.latency.seconds")
                .tag(AgentMetricsTags.CORPUS, corpus)
                .tag(AgentMetricsTags.PHASE, phase)
                .register(registry)
                .record(latency);
    }

    /** RAG 검색 지연 기록 — phase 미지정 시 {@code "all"} 사용 (inline backend 호환). */
    public void recordRagSearchLatency(String corpus, Duration latency) {
        recordRagSearchLatency(corpus, "all", latency);
    }

    /** RAG 검색 결과 없음 (miss) 기록 (corpus 별). */
    public void recordRagSearchMiss(String corpus) {
        Counter.builder("rag.search.miss.total")
                .tag(AgentMetricsTags.CORPUS, corpus)
                .register(registry)
                .increment();
    }

    /** RAG 검색 반환 청크 수 기록 (corpus 별). */
    public void recordRagChunkCount(String corpus, int count) {
        DistributionSummary.builder("rag.chunk.count")
                .tag(AgentMetricsTags.CORPUS, corpus)
                .register(registry)
                .record(count);
    }

    /**
     * RAG 인덱스 lag 기록 (corpus 별).
     *
     * <p>케이스 코퍼스 outbox 의 최고령 PENDING 건 lag(= 현재 시각 − created_at) 를 기록.
     * loan-service 의 {@code CaseOutboxLagMonitor} 에서 Gauge 로도 등록하므로
     * 여기서는 auto-loan-review 측에서 직접 측정 가능한 경우에만 호출한다.
     */
    public void recordRagIndexLag(String corpus, Duration lag) {
        Timer.builder("rag.index.lag.seconds")
                .tag(AgentMetricsTags.CORPUS, corpus)
                .register(registry)
                .record(lag);
    }

    /** 리포트 1건당 RAG citation 수 기록 (track 별). */
    public void recordRagCitationCount(Track track, int count) {
        DistributionSummary.builder("rag.citation.count.per.report")
                .tag(AgentMetricsTags.TRACK, track.name())
                .register(registry)
                .record(count);
    }

    // ── Canary 라우팅 ─────────────────────────────────────────────────────────

    /**
     * Canary 라우팅 기록 — backend={es|inline}.
     *
     * <p>메트릭: {@code ai.canary.routed.total{backend}}
     *
     * @param backend "es" 또는 "inline"
     */
    public void recordCanaryRouted(String backend) {
        Counter.builder("ai.canary.routed.total")
                .tag("backend", backend)
                .register(registry)
                .increment();
    }
}
