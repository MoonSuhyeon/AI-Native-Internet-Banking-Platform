package com.bank.ai.rag.api;

import com.bank.ai.rag.api.dto.ChunkBatchItem;

/**
 * 코퍼스 청크 적재 백엔드 추상 — Phase E (E3-2).
 *
 * <p>{@code ai.rag.backend} 값에 따라 단일 구현이 선택된다:
 * <ul>
 *   <li>{@code inline} (기본) → {@link PgVectorCorpusIndexer} (ai_embedding UPSERT)</li>
 *   <li>{@code es} → {@link EsCorpusIndexer} (코퍼스 alias 색인)</li>
 * </ul>
 *
 * <p>{@link EmbeddingBatchService} 는 PII 검사 후 본 인터페이스로 위임하므로
 * 백엔드 교체 시 적재 API 코드 변경이 없다.
 */
public interface CorpusIndexer {

    /** 청크 1건을 임베딩 후 활성 백엔드에 upsert. */
    void upsert(ChunkBatchItem item);
}
