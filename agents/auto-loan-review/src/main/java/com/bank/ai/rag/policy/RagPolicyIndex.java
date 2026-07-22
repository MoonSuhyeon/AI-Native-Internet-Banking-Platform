package com.bank.ai.rag.policy;

import com.bank.ai.llm.policy.PolicyIndex;
import com.bank.ai.rag.search.RagSearchBackend;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * RAG 기반 정책 인덱스 — phase-d-rag.md D2-1.
 *
 * <p>{@code ai.rag.enabled=true} 시에만 활성. ai_embedding.source_id 로 청크를 조회하여
 * PolicyIndex.exists / get 구현. GroundingValidator 가 {@code rag:} prefix 인용을 검증할 때 사용.
 *
 * <p>D2-6 에서 {@code rag:} prefix 라우팅 로직을 GroundingValidator 에 추가 시 본 빈이 연결됨.
 */
@Component
@ConditionalOnProperty(prefix = "ai.rag", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class RagPolicyIndex implements PolicyIndex {

    static final String CORPUS = "policy_regulation";

    private final RagSearchBackend ragSearchBackend;

    @Override
    public boolean exists(String sourceId) {
        return ragSearchBackend.existsBySourceId(CORPUS, sourceId);
    }

    @Override
    public Optional<PolicyEntry> get(String sourceId) {
        return ragSearchBackend.findBySourceId(CORPUS, sourceId)
                .map(chunk -> new PolicyEntry(
                        chunk.promptText(),
                        chunk.metadata().getOrDefault("source", "rag").toString()));
    }
}
