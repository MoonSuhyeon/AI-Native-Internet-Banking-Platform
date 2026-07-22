package com.bank.ai.llm.policy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Primary;

import java.util.Map;
import java.util.Optional;

/**
 * 인라인 정책 텍스트 인덱스 — application.yml {@code ai.policy.inline} 바인딩.
 *
 * <p>Phase 1.6 까지의 기본 구현체. {@code ai.rag.enabled=false} (기본값) 시 단독 PolicyIndex 빈.
 * {@code ai.rag.enabled=true} 시 {@link com.bank.ai.rag.policy.RagPolicyIndex} 와 공존하며,
 * {@code @Primary} 로 기본 주입 대상 유지.
 *
 * <p>이전 {@code PolicyIndex} record 로직을 그대로 이관 — API 비변경.
 */
@Primary
@ConfigurationProperties(prefix = "ai.policy")
public record InlinePolicyIndex(Map<String, PolicyIndex.PolicyEntry> inline) implements PolicyIndex {

    public InlinePolicyIndex {
        inline = inline != null ? Map.copyOf(inline) : Map.of();
    }

    @Override
    public boolean exists(String id) {
        return inline.containsKey(id);
    }

    @Override
    public Optional<PolicyIndex.PolicyEntry> get(String id) {
        return Optional.ofNullable(inline.get(id));
    }
}
