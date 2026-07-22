package com.bank.ai.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 감사 로그 설정 — application.yml {@code ai.audit.*}.
 */
@ConfigurationProperties(prefix = "ai.audit")
public record AuditLogProperties(
        boolean enabled,
        boolean includeRawLlmResponse
) {
    public AuditLogProperties() {
        this(true, false);
    }
}
