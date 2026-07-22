package com.bank.ai.llm.support;

import com.bank.ai.llm.client.LlmRequest;
import com.bank.ai.llm.config.LlmProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 호출 토큰 비용 미터링 + 일일 cap 강제 — plan/llm-pipeline.md §9.
 *
 * <h2>제공 메트릭 (5종)</h2>
 * <table border="1">
 * <tr><th>이름</th><th>종류</th><th>태그</th><th>의미</th></tr>
 * <tr><td>{@code llm.tokens.input}</td><td>Counter</td><td>model, prompt_id</td><td>입력 토큰 누적</td></tr>
 * <tr><td>{@code llm.tokens.output}</td><td>Counter</td><td>model, prompt_id</td><td>출력 토큰 누적</td></tr>
 * <tr><td>{@code llm.calls.total}</td><td>Counter</td><td>prompt_id, status</td><td>호출 수 (success/error/fallback/cap_exceeded)</td></tr>
 * <tr><td>{@code llm.latency}</td><td>Timer</td><td>prompt_id</td><td>응답 시간 분포</td></tr>
 * <tr><td>{@code llm.daily.tokens.total}</td><td>Gauge</td><td>—</td><td>당일 누적 입출력 토큰 합</td></tr>
 * </table>
 *
 * <h2>일일 cap 강제</h2>
 * {@link #checkCapOrThrow(LlmRequest)} 가 매 LLM 호출 직전에 호출된다.
 * 당일 누적 토큰 + 이번 호출 예상 입력이 {@code ai.llm.daily-token-cap} 초과 시
 * {@link LlmCostExceededException} 을 던진다. 자정 기준으로 날짜가 바뀌면 카운터 리셋.
 *
 * <h2>토큰 수 추정</h2>
 * 운영 provider (Vertex·Anthropic) 는 응답 헤더에 실제 토큰을 반환하지만 MVP 단계에서는
 * 문자 수 기반 추정 사용 (4 chars ≈ 1 token). {@link #record} 호출 시 실제 토큰을 전달하면
 * 그대로 기록; 0 이면 추정값 재사용.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmCostMeter {

    /** 토큰 추정 단위 — 4 chars ≈ 1 token (GPT tokenizer 휴리스틱, 다국어 보정 불포함). */
    static final int CHARS_PER_TOKEN = 4;

    /** 상태 태그 값. */
    public static final String STATUS_SUCCESS    = "success";
    public static final String STATUS_ERROR      = "error";
    public static final String STATUS_FALLBACK   = "fallback";
    public static final String STATUS_CAP_EXCEEDED = "cap_exceeded";

    private final MeterRegistry meterRegistry;
    private final LlmProperties props;

    /** 당일 누적 토큰 (input + output). 자정 기준 날짜 변경 시 0 으로 리셋. */
    private final AtomicLong dailyTotalTokens = new AtomicLong(0L);

    /** 마지막 리셋 날짜. volatile — 단일 write, 복수 read 패턴. */
    private volatile LocalDate currentDate = LocalDate.now();

    @PostConstruct
    public void registerGauge() {
        // Gauge: 당일 누적 토큰 총량 (cap 경보 대시보드용)
        meterRegistry.gauge("llm.daily.tokens.total", dailyTotalTokens, AtomicLong::get);
        log.info("LlmCostMeter 초기화 — daily-token-cap={:,}", props.dailyTokenCap());
    }

    // ────────────────────────────────────────────────────────────
    // Public API
    // ────────────────────────────────────────────────────────────

    /**
     * LLM 호출 직전 cap 체크. 초과 시 {@link LlmCostExceededException} 던짐.
     *
     * <p>입력 토큰 수를 낙관적으로 dailyTotal 에 더한다. 이후 {@link #record} 에서 실제 값으로
     * 보정(추가)한다. cap = 0 이면 무제한 (검사 건너뜀).
     *
     * @param request 이미 구성된 LLM 요청 (system + userContent 로 입력 토큰 추정)
     * @throws LlmCostExceededException 일일 cap 초과
     */
    public void checkCapOrThrow(LlmRequest request) {
        if (props.dailyTokenCap() <= 0) return;  // 0 = 무제한

        resetIfNewDay();

        long estimated = estimateInputTokens(request);
        long current = dailyTotalTokens.get();

        if (current + estimated > props.dailyTokenCap()) {
            long snapshot = dailyTotalTokens.get();
            log.warn("LlmCostMeter: 일일 cap 초과 — promptId={} 누적={} cap={}",
                    request.promptId(), snapshot, props.dailyTokenCap());
            countCall(request.promptId(), STATUS_CAP_EXCEEDED);
            throw new LlmCostExceededException(snapshot, props.dailyTokenCap());
        }
        // 낙관적 선점 — 호출이 실패하더라도 일부 토큰은 소비된 것으로 취급
        dailyTotalTokens.addAndGet(estimated);
    }

    /**
     * LLM 호출 완료 후 메트릭 기록.
     *
     * @param request      원본 요청 (promptId, model 태그용)
     * @param model        실제 사용된 모델명 (태그)
     * @param inputTokens  실제 입력 토큰 수 (0 이면 추정값 사용)
     * @param outputTokens 실제 출력 토큰 수 (0 이면 추정값 사용)
     * @param latencyMs    응답 지연 ms
     * @param status       {@link #STATUS_SUCCESS} / {@link #STATUS_ERROR} / {@link #STATUS_FALLBACK}
     */
    public void record(LlmRequest request, String model,
                       int inputTokens, int outputTokens,
                       long latencyMs, String status) {

        String promptId = request.promptId();

        // 실제 토큰 값이 있으면 반영; 없으면 추정
        int actualInput  = inputTokens  > 0 ? inputTokens  : (int) estimateInputTokens(request);
        int actualOutput = outputTokens > 0 ? outputTokens : request.maxTokens() / 2;

        // input token counter
        meterRegistry.counter("llm.tokens.input",
                "model", model, "prompt_id", promptId)
                .increment(actualInput);

        // output token counter
        meterRegistry.counter("llm.tokens.output",
                "model", model, "prompt_id", promptId)
                .increment(actualOutput);

        // daily total 보정 (checkCapOrThrow 에서 input 만 선점했으므로 output 추가)
        if (STATUS_SUCCESS.equals(status)) {
            dailyTotalTokens.addAndGet(actualOutput);
        }

        // call counter (by status)
        countCall(promptId, status);

        // latency timer (success / error 만 — fallback/cap 은 실제 LLM 호출 없음)
        if (!STATUS_FALLBACK.equals(status) && !STATUS_CAP_EXCEEDED.equals(status)) {
            meterRegistry.timer("llm.latency", "prompt_id", promptId)
                    .record(latencyMs, TimeUnit.MILLISECONDS);
        }

        log.debug("LlmCostMeter: promptId={} model={} in={} out={} latency={}ms status={}",
                promptId, model, actualInput, actualOutput, latencyMs, status);
    }

    /**
     * 당일 누적 토큰 수 (테스트·모니터링용).
     */
    public long getDailyTotalTokens() {
        resetIfNewDay();
        return dailyTotalTokens.get();
    }

    // ────────────────────────────────────────────────────────────
    // Internal helpers
    // ────────────────────────────────────────────────────────────

    private void resetIfNewDay() {
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDate)) {
            // double-checked locking 대신 단순 volatile write — 리셋이 1회 초과 발생해도
            // 큰 문제 없음 (보수적 토큰 계산이라 한번 리셋 되면 충분)
            synchronized (this) {
                if (!today.equals(currentDate)) {
                    log.info("LlmCostMeter: 날짜 변경 ({} → {}) — 일일 토큰 카운터 리셋",
                            currentDate, today);
                    dailyTotalTokens.set(0L);
                    currentDate = today;
                }
            }
        }
    }

    private static long estimateInputTokens(LlmRequest request) {
        int chars = (request.system() != null ? request.system().length() : 0)
                  + (request.userContent() != null ? request.userContent().length() : 0);
        return Math.max(1L, chars / CHARS_PER_TOKEN);
    }

    private void countCall(String promptId, String status) {
        meterRegistry.counter("llm.calls.total",
                "prompt_id", promptId, "status", status)
                .increment();
    }
}
