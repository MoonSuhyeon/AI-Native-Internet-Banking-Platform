package com.bank.ai.rag.seed;

import com.bank.ai.rag.RagProperties;
import com.bank.ai.rag.embedding.EmbeddingClient;
import com.bank.ai.rag.seed.PolicyCorpusChunkProvider.PolicyChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 정책 코퍼스 P1 seed (pgvector) — 기동 시 1회 실행 (멱등).
 *
 * <p>{@link PolicyCorpusChunkProvider} 가 만든 청크를 pgvector {@code ai_embedding} 에 적재.
 * {@code ON CONFLICT DO NOTHING} 으로 중복 기동 시 재적재 없음.
 *
 * <p>{@code ai.rag.backend=inline} (기본) 에서만 빈 등록. 실제 적재는 {@code ai.rag.enabled=true}
 * 일 때만 수행 — 비활성 시 즉시 반환. ES 백엔드는 {@code EsPolicyCorpusSeedLoader} 가 담당.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.rag", name = "backend", havingValue = "inline", matchIfMissing = true)
public class PolicyCorpusSeedLoader implements ApplicationRunner {

    static final String CORPUS = "policy_regulation";
    static final String EMBEDDING_MODEL = "text-embedding-005";
    static final LocalDate EFFECTIVE_DATE = LocalDate.of(2026, 4, 1);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PolicyCorpusChunkProvider chunkProvider;
    private final JdbcClient jdbcClient;
    private final EmbeddingClient embeddingClient;
    private final RagProperties ragProps;
    private final Executor executor;

    public PolicyCorpusSeedLoader(PolicyCorpusChunkProvider chunkProvider,
                                  JdbcClient jdbcClient,
                                  EmbeddingClient embeddingClient,
                                  RagProperties ragProps,
                                  @Qualifier("llmExecutor") Executor executor) {
        this.chunkProvider = chunkProvider;
        this.jdbcClient = jdbcClient;
        this.embeddingClient = embeddingClient;
        this.ragProps = ragProps;
        this.executor = executor;
    }

    /**
     * 기동 즉시 반환 — 실제 seed 작업은 executor 에 위임 (readiness probe 미통과 방지).
     * ON CONFLICT DO NOTHING 으로 멱등하므로 재기동 시 재적재 없음.
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!ragProps.enabled()) {
            log.debug("PolicyCorpusSeedLoader: ai.rag.enabled=false — seed 스킵");
            return;
        }
        log.info("PolicyCorpusSeedLoader: 정책 코퍼스 P1 seed 비동기 시작 (pgvector)");
        CompletableFuture.runAsync(this::doSeed, executor)
                .exceptionally(e -> {
                    log.error("PolicyCorpusSeedLoader: seed 실패", e);
                    return null;
                });
    }

    private void doSeed() {
        log.info("PolicyCorpusSeedLoader: seed 작업 시작");
        int count = 0;
        for (PolicyChunk chunk : chunkProvider.buildChunks()) {
            upsert(chunk);
            count++;
        }
        log.info("PolicyCorpusSeedLoader: seed 완료 — {} chunks (또는 이미 존재)", count);
    }

    // ─────────────────────────────────────────────────────────────────────

    private void upsert(PolicyChunk chunk) {
        float[] vec = embeddingClient.embed(chunk.chunkText());
        String vecLiteral = toVectorLiteral(vec);

        jdbcClient.sql("""
                INSERT INTO ai_embedding
                    (corpus, source_id, chunk_seq, chunk_text, chunk_summary,
                     embedding, embedding_model, metadata, fts_tokens,
                     effective_date, is_active)
                VALUES
                    (:corpus, :sourceId, :chunkSeq, :chunkText, :summary,
                     CAST(:embedding AS vector), :model, :metadata::jsonb,
                     to_tsvector('simple', :chunkText),
                     :effectiveDate, true)
                ON CONFLICT (corpus, source_id, chunk_seq, embedding_model) DO NOTHING
                """)
                .param("corpus", CORPUS)
                .param("sourceId", chunk.sourceId())
                .param("chunkSeq", chunk.chunkSeq())
                .param("chunkText", chunk.chunkText())
                .param("summary", chunk.summary())
                .param("embedding", vecLiteral)
                .param("model", EMBEDDING_MODEL)
                .param("metadata", toJson(chunk.metadata()))
                .param("effectiveDate", EFFECTIVE_DATE)
                .update();
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String toVectorLiteral(float[] vec) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
