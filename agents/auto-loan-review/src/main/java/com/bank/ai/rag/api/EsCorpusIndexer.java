package com.bank.ai.rag.api;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.bank.ai.rag.api.dto.ChunkBatchItem;
import com.bank.ai.rag.embedding.EmbeddingClient;
import com.bank.ai.rag.es.config.EsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 코퍼스 적재 — Phase E (E3-2).
 *
 * <p>코퍼스 식별자를 alias 로 매핑해 청크를 색인. 문서 id 를 {@code sourceId_chunkSeq} 로
 * 고정해 재적재 시 덮어쓰기(멱등)된다.
 *
 * <p>{@code ai.rag.backend=es} 에서만 빈 등록.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.rag", name = "backend", havingValue = "es")
public class EsCorpusIndexer implements CorpusIndexer {

    static final String EMBEDDING_MODEL = "text-embedding-005";

    private final ElasticsearchClient esClient;
    private final EmbeddingClient embeddingClient;
    private final EsProperties esProps;

    @Override
    public void upsert(ChunkBatchItem item) {
        String index = resolveAlias(item.corpus());
        List<Float> embedding = toFloatList(embeddingClient.embed(item.chunkText()));

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("corpus", item.corpus());
        doc.put("source_id", item.sourceId());
        doc.put("chunk_seq", item.chunkSeq());
        doc.put("chunk_text", item.chunkText());
        doc.put("chunk_summary", item.summary());
        doc.put("metadata", item.metadata());
        doc.put("embedding", embedding);
        doc.put("embedding_model", EMBEDDING_MODEL);
        doc.put("created_at", Instant.now().toString());

        String docId = "%s_%d".formatted(item.sourceId(), item.chunkSeq());
        try {
            esClient.index(i -> i.index(index).id(docId).document(doc));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "ES 색인 실패 corpus=%s id=%s".formatted(item.corpus(), docId), e);
        }
    }

    private String resolveAlias(String corpus) {
        EsProperties.EsIndexNames idx = esProps.indexes();
        return switch (corpus) {
            case "policy_regulation" -> idx.policy();
            case "similar_cases" -> idx.cases();
            case "internal_faq" -> idx.faq();
            default -> corpus;
        };
    }

    private static List<Float> toFloatList(float[] vec) {
        List<Float> list = new ArrayList<>(vec.length);
        for (float v : vec) list.add(v);
        return list;
    }
}
