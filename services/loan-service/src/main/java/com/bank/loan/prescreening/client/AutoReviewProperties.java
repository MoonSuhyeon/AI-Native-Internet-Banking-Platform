package com.bank.loan.prescreening.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "auto-review")
public record AutoReviewProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("http://auto-loan-review:8089") String baseUrl,
        @DefaultValue("") String internalToken,
        @DefaultValue("5000") int connectTimeoutMs,
        @DefaultValue("30000") int readTimeoutMs
) {}
