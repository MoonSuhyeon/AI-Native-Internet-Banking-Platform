package com.bank.ai.rag.api;

import com.bank.ai.rag.api.dto.ChunkBatchItem;
import com.bank.ai.rag.embedding.EmbeddingClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * pgvector 코퍼스 적재 — {@code ai_embedding} UPSERT (Phase D D3-1 로직 이관).
 *
 * <p>{@code ai.rag.backend=inline} (기본) 에서만 빈 등록. pgvector SQL 포함 — H2 불가.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.rag", name = "backend", havingValue = "inline", matchIfMissing = true)
public class PgVectorCorpusIndexer implements CorpusIndexer {

    static final String DEFAULT_MODEL = "text-embedding-005";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcClient jdbcClient;
    private final EmbeddingClient embeddingClient;

    @Override
    public void upsert(ChunkBatchItem item) {
        float[] vec = embeddingClient.embed(item.chunkText());
        String vecLiteral = toVectorLiteral(vec);

        jdbcClient.sql("""
                INSERT INTO ai_embedding
                    (corpus, source_id, chunk_seq, chunk_text, chunk_summary,
                     embedding, embedding_model, metadata, fts_tokens,
                     effective_date, is_active)
                VALUES
                    (:corpus, :sourceId, :chunkSeq, :chunkText, :summary,
                     CAST(:embedding AS vector), :model, :metadata::jsonb,
                     to_tsvector('simple', :chunkText), :effectiveDate, true)
                ON CONFLICT (corpus, source_id, chunk_seq, embedding_model) DO UPDATE SET
                    chunk_text    = EXCLUDED.chunk_text,
                    chunk_summary = EXCLUDED.chunk_summary,
                    embedding     = EXCLUDED.embedding,
                    metadata      = EXCLUDED.metadata,
                    fts_tokens    = EXCLUDED.fts_tokens,
                    updated_at    = now()
                """)
                .param("corpus", item.corpus())
                .param("sourceId", item.sourceId())
                .param("chunkSeq", item.chunkSeq())
                .param("chunkText", item.chunkText())
                .param("summary", item.summary())
                .param("embedding", vecLiteral)
                .param("model", DEFAULT_MODEL)
                .param("metadata", toJson(item.metadata()))
                .param("effectiveDate", LocalDate.now())
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
