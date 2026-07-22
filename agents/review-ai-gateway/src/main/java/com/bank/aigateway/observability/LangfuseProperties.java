package com.bank.aigateway.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "langfuse")
public record LangfuseProperties(
        boolean enabled,
        String secretKey,
        String publicKey,
        String host
) {
    public LangfuseProperties {
        if (host == null || host.isBlank()) host = "http://localhost:3001";
    }
}
