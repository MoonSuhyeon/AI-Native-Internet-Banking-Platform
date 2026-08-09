package com.bank.loan.advisory.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "advisory.rag.embed")
public record AdvisoryRagProperties(
        @DefaultValue("stub") String provider,
        @DefaultValue("text-embedding-3-small") String model,
        @DefaultValue("1536") int dimension,
        OpenAi openai
) {
    public record OpenAi(
            @DefaultValue("https://api.openai.com") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("2000") int connectTimeoutMs,
            @DefaultValue("10000") int readTimeoutMs,
            @DefaultValue("3") int maxAttempts,
            @DefaultValue("300") long retryBackoffMs
    ) {}
}
