package com.bank.ai.rag.api.dto;

import java.util.List;

/** POST /api/internal/embeddings/batch 요청 본문 — D3-1. */
public record EmbeddingBatchRequest(List<ChunkBatchItem> items) {
    public EmbeddingBatchRequest {
        items = items != null ? List.copyOf(items) : List.of();
    }
}
