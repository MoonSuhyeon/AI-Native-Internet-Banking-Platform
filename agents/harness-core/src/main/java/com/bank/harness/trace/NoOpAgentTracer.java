package com.bank.harness.trace;

import java.time.Instant;
import java.util.Map;

/**
 * 추적을 끈 환경용 구현.
 *
 * <p>에이전트 코드가 {@code if (tracer != null)} 같은 분기를 갖지 않게 한다.
 * 관측이 꺼졌다는 사실이 도메인 코드에 새어나가면 안 된다.
 */
public class NoOpAgentTracer implements AgentTracer {

    private static final TraceScope NOOP_SCOPE = new TraceScope() {
        @Override public String traceId() { return "00000000000000000000000000000000"; }
        @Override public void recordGeneration(String name, String model, Object in, Object out,
                                               Integer inTok, Integer outTok,
                                               Instant start, Instant end) { }
        @Override public void recordSpan(String name, Object in, Object out,
                                         Instant start, Instant end) { }
        @Override public void recordError(Throwable error) { }
        @Override public void close() { }
    };

    @Override
    public TraceScope startTrace(String name, Map<String, Object> metadata) {
        return NOOP_SCOPE;
    }

    @Override
    public String currentTraceId() {
        return null;
    }
}
