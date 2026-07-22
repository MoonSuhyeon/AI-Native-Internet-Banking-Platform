package com.bank.ai.rag.embedding;

import java.util.List;

/**
 * provider 중립 임베딩 인터페이스 — phase-d-rag.md D1-3.
 *
 * <p>구현체:
 * <ul>
 *   <li>{@code StubEmbeddingClient} — 결정론 벡터 (테스트·로컬)</li>
 *   <li>{@code SpringAiEmbeddingClient} — Vertex AI text-embedding-005</li>
 * </ul>
 * {@code ai.rag.embedding.provider} 값으로 활성 빈 결정.
 */
public interface EmbeddingClient {

    /** 단건 임베딩. */
    float[] embed(String text);

    /** 배치 임베딩 (인덱싱 시 효율). */
    List<float[]> embedAll(List<String> texts);
}
