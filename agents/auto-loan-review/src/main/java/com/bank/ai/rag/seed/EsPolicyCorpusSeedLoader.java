package com.bank.ai.rag.seed;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.bank.ai.rag.RagProperties;
import com.bank.ai.rag.embedding.EmbeddingClient;
import com.bank.ai.rag.es.config.EsProperties;
import com.bank.ai.rag.seed.PolicyCorpusChunkProvider.PolicyChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 정책 코퍼스 P1 seed (Elasticsearch) — Phase E (E3-1).
 *
 * <p>{@link PolicyCorpusChunkProvider} 가 만든 동일 청크를 임베딩 후 {@code kb_policy} 인덱스에 색인.
 * 문서 id 를 {@code sourceId_chunkSeq} 로 고정해 재기동 시 덮어쓰기(멱등)된다.
 *
 * <p>{@code ai.rag.backend=es} 에서만 빈 등록. 실제 적재는 {@code ai.rag.enabled=true} 일 때만 수행.
 * pgvector 백엔드는 {@link PolicyCorpusSeedLoader} 가 담당.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.rag", name = "backend", havingValue = "es")
public class EsPolicyCorpusSeedLoader implements ApplicationRunner {

    static final String CORPUS = "policy_regulation";
    static final String EMBEDDING_MODEL = "text-embedding-005";

    private final PolicyCorpusChunkProvider chunkProvider;
    private final ElasticsearchClient esClient;
    private final EmbeddingClient embeddingClient;
    private final EsProperties esProps;
    private final RagProperties ragProps;
    private final Executor executor;

    public EsPolicyCorpusSeedLoader(PolicyCorpusChunkProvider chunkProvider,
                                    ElasticsearchClient esClient,
                                    EmbeddingClient embeddingClient,
                                    EsProperties esProps,
                                    RagProperties ragProps,
                                    @Qualifier("llmExecutor") Executor executor) {
        this.chunkProvider = chunkProvider;
        this.esClient = esClient;
        this.embeddingClient = embeddingClient;
        this.esProps = esProps;
        this.ragProps = ragProps;
        this.executor = executor;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!ragProps.enabled()) {
            log.debug("EsPolicyCorpusSeedLoader: ai.rag.enabled=false — seed 스킵");
            return;
        }
        log.info("EsPolicyCorpusSeedLoader: 정책 코퍼스 P1 seed 비동기 시작 (ES)");
        CompletableFuture.runAsync(this::doSeed, executor)
                .exceptionally(e -> {
                    log.error("EsPolicyCorpusSeedLoader: seed 실패", e);
                    return null;
                });
    }

    /** 외부(테스트 등)에서 동기 적재가 필요할 때 사용. */
    public int doSeed() {
        String index = esProps.indexes().policy();
        int count = 0;
        for (PolicyChunk chunk : chunkProvider.buildChunks()) {
            indexChunk(index, chunk);
            count++;
        }
        refresh(index);
        log.info("EsPolicyCorpusSeedLoader: seed 완료 — {} chunks → {}", count, index);
        return count;
    }

    // ─────────────────────────────────────────────────────────────────────

    private void indexChunk(String index, PolicyChunk chunk) {
        List<Float> embedding = toFloatList(embeddingClient.embed(chunk.chunkText()));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("corpus", CORPUS);
        doc.put("source_id", chunk.sourceId());
        doc.put("chunk_seq", chunk.chunkSeq());
        doc.put("chunk_text", chunk.chunkText());
        doc.put("chunk_summary", chunk.summary());
        doc.put("metadata", chunk.metadata());
        doc.put("embedding", embedding);
        doc.put("embedding_model", EMBEDDING_MODEL);
        doc.put("created_at", Instant.now().toString());

        String docId = "%s_%d".formatted(chunk.sourceId(), chunk.chunkSeq());
        try {
            esClient.index(i -> i.index(index).id(docId).document(doc));
        } catch (Exception e) {
            log.error("EsPolicyCorpusSeedLoader: 색인 실패 id={}", docId, e);
        }
    }

    private void refresh(String index) {
        try {
            esClient.indices().refresh(r -> r.index(index));
        } catch (Exception e) {
            log.warn("EsPolicyCorpusSeedLoader: refresh 실패 index={}", index, e);
        }
    }

    private static List<Float> toFloatList(float[] vec) {
        List<Float> list = new ArrayList<>(vec.length);
        for (float v : vec) list.add(v);
        return list;
    }
}
