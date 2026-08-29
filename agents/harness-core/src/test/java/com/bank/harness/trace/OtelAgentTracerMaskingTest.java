package com.bank.harness.trace;

import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 추적에 <b>실제로</b> 무엇이 나가는지 본다.
 *
 * <p>마스킹 함수의 단위 테스트가 통과하는 것과, 추적기가 그 함수를 부르는 것은 다르다.
 * 실제로 자바 추적기는 {@code gen_ai.prompt} 와 {@code gen_ai.completion} 에 프롬프트와
 * 응답을 <b>그대로</b> 실었다 — 파이썬 쪽에는 마스킹이 있었고 자바 쪽에만 없었다.
 * 함수는 있는데 부르지 않는 형태라, 함수 테스트로는 절대 드러나지 않는다.
 *
 * <p>그래서 여기서는 내보낸 span 을 받아 속성을 직접 읽는다.
 */
@DisplayName("추적기 — 싣기 전에 가린다")
class OtelAgentTracerMaskingTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private OtelAgentTracer tracer;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        var sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        tracer = new OtelAgentTracer(sdk.getTracer("test"), "test-system");
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    /** 내보낸 모든 span 의 속성을 한 문자열로 모은다 — 어느 속성에 남든 잡는다. */
    private String exportedAttributes() {
        StringBuilder sb = new StringBuilder();
        exporter.getFinishedSpanItems()
                .forEach(span -> span.getAttributes().forEach((k, v) -> sb.append(k.getKey())
                        .append('=').append(v).append('\n')));
        return sb.toString();
    }

    @Test
    @DisplayName("프롬프트와 응답의 개인정보가 그대로 나가지 않는다")
    void masks_prompt_and_completion() {
        Instant now = Instant.now();
        try (var scope = tracer.startTrace("run", Map.of())) {
            scope.recordGeneration("llm", "gemini-2.0",
                    "홍길동님 계좌 110-223-456789 로 이체 요청",
                    "010-1234-5678 로 안내드렸습니다",
                    10, 20, now, now.plusMillis(5));
        }

        String attrs = exportedAttributes();
        assertThat(attrs)
                .as("계측을 켜는 것이 유출을 늘리는 일이 되면 안 된다")
                .doesNotContain("홍길동", "110-223-456789", "010-1234-5678");
        assertThat(attrs).contains("[NAME]", "[ACCT]", "[PHONE]");
    }

    @Test
    @DisplayName("도구 단계의 입출력도 가린다")
    void masks_step_payloads() {
        Instant now = Instant.now();
        try (var scope = tracer.startTrace("run", Map.of())) {
            scope.recordSpan("tool", "김철수님 조회", "주민 900101-1234567", now, now.plusMillis(5));
        }

        String attrs = exportedAttributes();
        assertThat(attrs).doesNotContain("김철수", "900101-1234567");
    }

    @Test
    @DisplayName("메타데이터의 고객번호는 가명으로 바뀐다")
    void pseudonymizes_metadata_identifiers() {
        // "9111" 은 어떤 정규식에도 걸리지 않는다. 이름으로 판단해야 잡힌다.
        try (var scope = tracer.startTrace("run", Map.of("customer_id", "9111"))) {
            // 루트 span 속성만 본다
        }

        String attrs = exportedAttributes();
        assertThat(attrs).doesNotContain("9111");
        assertThat(attrs).contains("cust_");
    }

    @Test
    @DisplayName("모델·토큰 수는 그대로 남는다")
    void keeps_operational_values() {
        // 다 가리면 추적을 켠 이유가 없어진다. 비용·지연을 가르는 축은 남아야 한다.
        Instant now = Instant.now();
        try (var scope = tracer.startTrace("run", Map.of())) {
            scope.recordGeneration("llm", "gemini-2.0", "질문", "답변", 10, 20, now, now.plusMillis(5));
        }

        String attrs = exportedAttributes();
        assertThat(attrs).contains("gemini-2.0", "gen_ai.usage.input_tokens=10");
    }
}
