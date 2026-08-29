package com.bank.harness.trace;

import com.bank.harness.pii.PiiMasker;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenTelemetry 기반 {@link AgentTracer} 구현.
 *
 * <p>LLM 호출은 GenAI 시맨틱 규약({@code gen_ai.*})으로 기록한다. 자체 필드명을 쓰면
 * 관측 도구마다 매핑을 따로 해야 하지만, 표준 속성이면 Langfuse·Phoenix 가 그대로 읽는다.
 * 이것이 도구 종속을 끊는 실질적인 지점이다.
 *
 * <p>입출력 본문은 크기를 제한해서 싣는다. 프롬프트와 응답 전문을 그대로 보내면
 * 추적 백엔드가 로그 저장소가 되고 비용이 폭증한다. 전문이 필요하면 감사 로그를 본다 —
 * 그쪽이 보존기간과 불변성을 책임지는 자리다.
 *
 * <p><b>싣기 전에 가린다.</b> 예전에는 프롬프트·응답·메타데이터를 그대로 실었다.
 * 계측을 켜는 순간 추적 저장소가 고객 데이터 사본이 됐다는 뜻이다 — 크기만 줄었을 뿐
 * 내용은 원문이었다. 지금은 모두 {@link PiiMasker} 를 지난다.
 *
 * <p>자르는 것과 가리는 것의 <b>순서가 중요하다.</b> 먼저 자르면 경계에 걸친 주민번호가
 * 반토막 나 정규식에 안 걸리고, 그 반토막이 그대로 나간다. 그래서 가린 뒤에 자른다.
 */
public class OtelAgentTracer implements AgentTracer {

    /** 추적에 싣는 입출력 본문 최대 길이. 넘으면 잘라서 표시한다. */
    private static final int MAX_PAYLOAD_CHARS = 4_000;

    private static final AttributeKey<String> GEN_AI_SYSTEM = AttributeKey.stringKey("gen_ai.system");
    private static final AttributeKey<String> GEN_AI_MODEL = AttributeKey.stringKey("gen_ai.request.model");
    private static final AttributeKey<Long> GEN_AI_IN_TOKENS = AttributeKey.longKey("gen_ai.usage.input_tokens");
    private static final AttributeKey<Long> GEN_AI_OUT_TOKENS = AttributeKey.longKey("gen_ai.usage.output_tokens");
    private static final AttributeKey<String> GEN_AI_PROMPT = AttributeKey.stringKey("gen_ai.prompt");
    private static final AttributeKey<String> GEN_AI_COMPLETION = AttributeKey.stringKey("gen_ai.completion");
    private static final AttributeKey<String> STEP_INPUT = AttributeKey.stringKey("harness.step.input");
    private static final AttributeKey<String> STEP_OUTPUT = AttributeKey.stringKey("harness.step.output");

    private final Tracer tracer;
    private final String systemName;

    /**
     * @param systemName {@code gen_ai.system} 값 (예: "gemini", "anthropic").
     *                   모델 제공자별 비용·지연을 가르는 축이 된다.
     */
    public OtelAgentTracer(Tracer tracer, String systemName) {
        this.tracer = tracer;
        this.systemName = systemName;
    }

    @Override
    public TraceScope startTrace(String name, Map<String, Object> metadata) {
        Span span = tracer.spanBuilder(name).startSpan();
        if (metadata != null) {
            // 메타데이터에도 고객번호가 들어온다. 이름을 함께 넘겨야 식별자로 판단된다.
            metadata.forEach((k, v) -> span.setAttribute("harness." + k,
                    truncate(PiiMasker.scrubAttribute(k, v))));
        }
        return new OtelTraceScope(span);
    }

    @Override
    public String currentTraceId() {
        String id = Span.current().getSpanContext().getTraceId();
        return id.matches("0+") ? null : id;
    }

    private final class OtelTraceScope implements TraceScope {

        private final Span span;
        private final Scope scope;

        private OtelTraceScope(Span span) {
            this.span = span;
            this.scope = span.makeCurrent();
        }

        @Override
        public String traceId() {
            return span.getSpanContext().getTraceId();
        }

        @Override
        public void recordGeneration(String name, String model,
                                     Object input, Object output,
                                     Integer inputTokens, Integer outputTokens,
                                     Instant startTime, Instant endTime) {
            Span child = tracer.spanBuilder(name)
                    .setStartTimestamp(startTime)
                    .startSpan();
            child.setAttribute(GEN_AI_SYSTEM, systemName);
            if (model != null) {
                child.setAttribute(GEN_AI_MODEL, model);
            }
            if (inputTokens != null) {
                child.setAttribute(GEN_AI_IN_TOKENS, inputTokens.longValue());
            }
            if (outputTokens != null) {
                child.setAttribute(GEN_AI_OUT_TOKENS, outputTokens.longValue());
            }
            child.setAttribute(GEN_AI_PROMPT, scrubbed(input));
            child.setAttribute(GEN_AI_COMPLETION, scrubbed(output));
            child.end(endTime);
        }

        @Override
        public void recordSpan(String name, Object input, Object output,
                               Instant startTime, Instant endTime) {
            Span child = tracer.spanBuilder(name)
                    .setStartTimestamp(startTime)
                    .startSpan();
            child.setAttribute(STEP_INPUT, scrubbed(input));
            child.setAttribute(STEP_OUTPUT, scrubbed(output));
            child.end(endTime);
        }

        @Override
        public void recordError(Throwable error) {
            span.setStatus(StatusCode.ERROR, error.getMessage() == null ? "" : error.getMessage());
            span.recordException(error);
        }

        @Override
        public void close() {
            scope.close();
            span.end();
        }
    }

    /** 가린 뒤에 자른다. 순서를 바꾸면 경계에 걸친 값이 반토막으로 새어 나간다. */
    private static String scrubbed(Object value) {
        return value == null ? "" : truncate(PiiMasker.mask(value.toString()));
    }

    private static String truncate(Object value) {
        if (value == null) {
            return "";
        }
        String s = value.toString();
        return s.length() <= MAX_PAYLOAD_CHARS
                ? s
                : s.substring(0, MAX_PAYLOAD_CHARS) + "…(" + s.length() + "자 중 앞부분)";
    }

    /** 테스트에서 내보내기 플러시가 필요할 때 쓰는 편의 상수. */
    public static final long DEFAULT_FLUSH_TIMEOUT = TimeUnit.SECONDS.toMillis(5);
}
