package com.bank.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {

    @Bean
    KeyResolver rateLimitKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                return reactor.core.publisher.Mono.just("user:" + userId);
            }
            var remoteAddress = exchange.getRequest().getRemoteAddress();
            String ip = remoteAddress != null && remoteAddress.getAddress() != null
                    ? remoteAddress.getAddress().getHostAddress() : "unknown";
            return reactor.core.publisher.Mono.just("ip:" + ip);
        };
    }
}
