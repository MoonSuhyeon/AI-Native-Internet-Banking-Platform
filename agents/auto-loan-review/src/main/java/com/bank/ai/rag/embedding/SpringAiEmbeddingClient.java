package com.bank.ai.rag.embedding;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring AI 기반 임베딩 클라이언트 — Vertex AI text-embedding-005.
 *
 * <p>{@code ai.rag.embedding.provider=vertex} 시 활성.
 * {@code spring.ai.vertex.ai.embedding.*} 설정으로 project-id·location·model 지정.
 */
@Component
@ConditionalOnProperty(prefix = "ai.rag.embedding", name = "provider", havingValue = "vertex")
@RequiredArgsConstructor
public class SpringAiEmbeddingClient implements EmbeddingClient {

    private final EmbeddingModel embeddingModel;

    @Override
    public float[] embed(String text) {
        // Spring AI 1.0.0: EmbeddingModel.embed(String) → float[]
        return embeddingModel.embed(text);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        // Spring AI 1.0.0: embedForResponse → EmbeddingResponse → List<Embedding>
        // Spring AI 1.0.0: Embedding.getOutput() → float[]
        return embeddingModel.embedForResponse(texts).getResults().stream()
                .map(e -> e.getOutput())
                .toList();
    }
}
