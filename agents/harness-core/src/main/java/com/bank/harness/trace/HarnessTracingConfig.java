package com.bank.harness.trace;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;

import java.util.Arrays;

/**
 * 추적 배선.
 *
 * <p>에이전트는 {@link AgentTracer} 하나만 주입받는다. OTLP 엔드포인트가 Langfuse 인지
 * Phoenix 인지 Jaeger 인지는 설정값의 문제이지 코드의 문제가 아니다 —
 * 이 분리가 통합의 핵심이다. 이전에는 Langfuse HTTP 클라이언트를 직접 들고 있어
 * 다른 도구를 붙이려면 코드를 고쳐야 했다.
 */
// @Configuration 이 아니라 @AutoConfiguration 이다.
// harness-core 는 라이브러리라 소비자의 컴포넌트 스캔 경로에 들어가지 않는다.
// 자동설정으로 등록해야 에이전트가 스캔 범위를 신경 쓰지 않고 주입만 받을 수 있다.
@AutoConfiguration
@EnableConfigurationProperties(HarnessTracingProperties.class)
public class HarnessTracingConfig {

    @Bean
    public AgentTracer agentTracer(HarnessTracingProperties props) {
        if (!props.enabled()) {
            return new NoOpAgentTracer();
        }

        var exporterBuilder = OtlpHttpSpanExporter.builder()
                .setEndpoint(props.endpoint());
        parseHeaders(props.headers()).forEach(exporterBuilder::addHeader);

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(exporterBuilder.build()).build())
                .setResource(Resource.getDefault().merge(Resource.create(
                        Attributes.of(AttributeKey.stringKey("service.name"), props.service()))))
                .build();

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        // JVM 종료 시 버퍼에 남은 스팬을 내보낸다. 없으면 짧게 도는 배치의 추적이 통째로 사라진다.
        Runtime.getRuntime().addShutdownHook(new Thread(tracerProvider::close));

        return new OtelAgentTracer(sdk.getTracer("com.bank.harness"), props.system());
    }

    /** {@code "K1=V1,K2=V2"} 형태를 헤더 맵으로. 값에 '='가 들어갈 수 있어 첫 번째만 자른다. */
    private static java.util.Map<String, String> parseHeaders(String raw) {
        var map = new java.util.LinkedHashMap<String, String>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(pair -> {
                    int i = pair.indexOf('=');
                    if (i > 0) {
                        map.put(pair.substring(0, i).trim(), pair.substring(i + 1).trim());
                    }
                });
        return map;
    }
}
