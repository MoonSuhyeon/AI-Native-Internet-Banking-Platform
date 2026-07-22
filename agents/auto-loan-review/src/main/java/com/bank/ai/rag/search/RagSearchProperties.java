package com.bank.ai.rag.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 검색 파라미터 — application.yml {@code ai.rag.search}.
 *
 * @param alpha               벡터 가중치 (FTS 가중치 = 1 - alpha). 기본 0.7
 * @param similarityThreshold 이 값 미만 결과 제외. 기본 0.5
 * @param defaultK            기본 반환 건수. 기본 5
 */
@ConfigurationProperties(prefix = "ai.rag.search")
public record RagSearchProperties(
        double alpha,
        double similarityThreshold,
        int defaultK
) {
    public RagSearchProperties {
        if (alpha < 0 || alpha > 1) throw new IllegalArgumentException("alpha must be in [0, 1]");
        if (similarityThreshold < 0 || similarityThreshold > 1)
            throw new IllegalArgumentException("similarityThreshold must be in [0, 1]");
        if (defaultK <= 0) throw new IllegalArgumentException("defaultK must be positive");
    }
}
