package com.bank.harness.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 추적 설정.
 *
 * @param enabled  꺼두면 NoOp 구현이 뜬다.
 * @param endpoint OTLP 수집 지점. Langfuse 는 {@code /api/public/otel} 로 OTLP 를 받고,
 *                 Phoenix·Jaeger 등도 같은 프로토콜을 쓴다. 도구를 바꾸려면 이 값만 바꾼다.
 * @param headers  인증 헤더. Langfuse 는 Basic 인증을 쓴다 ("Authorization=Basic xxx").
 * @param system   {@code gen_ai.system} 값 (예: gemini, anthropic).
 * @param service  서비스 이름. 어느 에이전트의 실행인지 가른다.
 */
@ConfigurationProperties(prefix = "harness.tracing")
public record HarnessTracingProperties(
        boolean enabled,
        String endpoint,
        String headers,
        String system,
        String service
) {
    public HarnessTracingProperties {
        if (endpoint == null || endpoint.isBlank()) endpoint = "http://localhost:4318/v1/traces";
        if (system == null || system.isBlank()) system = "unknown";
        if (service == null || service.isBlank()) service = "agent";
    }
}
