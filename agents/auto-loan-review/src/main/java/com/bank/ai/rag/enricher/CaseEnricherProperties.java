package com.bank.ai.rag.enricher;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 케이스 임베딩 엔리처 설정 — Phase E (E3-6).
 *
 * <p>raw topic → 임베딩 추가 → enriched topic 파이프라인 활성 여부 및 토픽명 관리.
 *
 * @param enabled       엔리처 활성 여부
 * @param rawTopic      소비할 raw 토픽 (loan-service outbox dispatcher 발행처)
 * @param enrichedTopic 임베딩 추가 후 재발행할 토픽 (Kafka Connect ES sink 소비처)
 */
@ConfigurationProperties(prefix = "ai.case-enricher")
public record CaseEnricherProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("loan-review.case-indexed.v1") String rawTopic,
        @DefaultValue("loan-review.case-indexed-enriched.v1") String enrichedTopic
) {}
