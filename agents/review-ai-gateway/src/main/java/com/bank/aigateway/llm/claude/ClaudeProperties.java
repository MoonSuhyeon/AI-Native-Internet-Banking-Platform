package com.bank.aigateway.llm.claude;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "llm.claude")
public class ClaudeProperties {

    private String baseUrl;
    private String apiKey;
    private String model;
    private int maxTokens;
    private long timeoutMs;
    private long connectTimeoutMs;
    private int maxAttempts;
    private long retryBackoffMs;
}
