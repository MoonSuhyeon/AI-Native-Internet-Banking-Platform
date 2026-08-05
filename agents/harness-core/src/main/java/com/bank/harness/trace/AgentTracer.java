package com.bank.harness.trace;

import java.time.Instant;
import java.util.Map;

/**
 * 에이전트 실행 추적.
 *
 * <p>지금까지는 에이전트마다 Langfuse 클라이언트를 직접 들고 있었다.
 * auto-loan-review 에 128줄, review-ai-gateway 에 98줄, fraud-investigation 에 64줄.
 * 공개 API 가 거의 같은데도 세 벌이었고, 무엇보다 <b>Langfuse 에 잠겨 있어</b>
 * 다른 관측 도구를 붙일 수 없었다.
 *
 * <p>이 인터페이스는 "무엇을 기록하는가"만 정한다. 어디로 보내는지는 구현이 정하고,
 * OpenTelemetry 구현을 쓰면 exporter 를 갈아끼우는 것으로 대상이 바뀐다.
 *
 * <p>도메인 타입은 들어오지 않는다. 심사 의견도 사기 점수도 모른다 —
 * 들어오는 순간 다른 도메인이 쓸 수 없게 된다.
 */
public interface AgentTracer {

    /**
     * 하나의 에이전트 실행을 연다. 반환된 스코프를 닫아야 기록이 확정된다.
     *
     * @param name     실행 이름 (예: "loan-prereview")
     * @param metadata 실행 단위 속성. 도메인 식별자는 값으로만 넣는다.
     */
    TraceScope startTrace(String name, Map<String, Object> metadata);

    /** 현재 열려 있는 실행의 식별자. 없으면 null. */
    String currentTraceId();

    /**
     * 하나의 에이전트 실행 범위. try-with-resources 로 쓴다.
     *
     * <pre>{@code
     * try (var scope = tracer.startTrace("loan-prereview", Map.of("applId", applId))) {
     *     scope.recordGeneration(...);
     * }
     * }</pre>
     */
    interface TraceScope extends AutoCloseable {

        String traceId();

        /**
         * LLM 호출 기록.
         *
         * <p>OpenTelemetry GenAI 시맨틱 규약({@code gen_ai.*})으로 나간다.
         * 표준 속성이라 Langfuse·Phoenix 어느 쪽이든 같은 필드로 읽는다.
         */
        void recordGeneration(String name, String model,
                              Object input, Object output,
                              Integer inputTokens, Integer outputTokens,
                              Instant startTime, Instant endTime);

        /** LLM 이 아닌 단계 기록 (RAG 검색, 툴 호출, 계산 등). */
        void recordSpan(String name, Object input, Object output,
                        Instant startTime, Instant endTime);

        /** 실행 실패 기록. 닫기 전에 호출하면 실행 전체가 오류로 표시된다. */
        void recordError(Throwable error);

        @Override
        void close();
    }
}
