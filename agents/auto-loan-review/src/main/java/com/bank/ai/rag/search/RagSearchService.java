package com.bank.ai.rag.search;

import com.bank.ai.langfuse.LangfuseService;
import com.bank.ai.metrics.AgentMetricsRecorder;
import com.bank.ai.rag.embedding.EmbeddingClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 하이브리드 검색 서비스 — 벡터 cosine + FTS BM25 가중합.
 *
 * <p>검색 SQL: rag-corpora.md §5.1 (CTE vec + fts → hybrid_score 내림차순).
 * metaFilter 는 {@code metadata @> :filter::jsonb} JSONB containment 로 처리.
 * similarity threshold 미만은 제외.
 *
 * <p>pgvector 전용 SQL 이라 H2 환경에서는 사용 불가.
 * 단위 테스트는 {@link #existsById} 등 단순 조회만 사용,
 * 하이브리드 검색 통합 테스트는 testcontainers + pgvector 로 별도 실행.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.rag", name = "backend", havingValue = "inline", matchIfMissing = true)
public class RagSearchService implements RagSearchBackend {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcClient jdbcClient;
    private final EmbeddingClient embeddingClient;
    private final RagSearchProperties props;
    private final AgentMetricsRecorder metricsRecorder;

    @Autowired(required = false)
    private LangfuseService langfuse;

    /**
     * 하이브리드 검색.
     *
     * @param corpus      코퍼스 식별자
     * @param query       자연어 질의
     * @param metaFilter  JSONB containment 필터 (null 허용 — 필터 없음)
     * @param k           반환 건수 (0 이하 시 defaultK 사용)
     * @return 유사도 점수 내림차순 Chunk 목록
     */
    public List<Chunk> search(String corpus, String query,
                              Map<String, Object> metaFilter, int k) {
        int limit = k > 0 ? k : props.defaultK();
        float[] queryVec = embeddingClient.embed(query);
        String vecLiteral = toVectorLiteral(queryVec);
        String metaJson = metaFilter != null && !metaFilter.isEmpty()
                ? toJsonb(metaFilter) : null;
        String tsQuery = toTsQuery(query); // null 이면 FTS 스킵

        Map<String, Object> optionalParams = new HashMap<>();
        if (metaJson != null) optionalParams.put("metaFilter", metaJson);
        if (tsQuery != null)  optionalParams.put("queryTsq", tsQuery);

        Instant start = Instant.now();
        try {
            List<Chunk> results = jdbcClient.sql(buildSql(metaJson != null, tsQuery != null))
                    .param("corpus", corpus)
                    .param("queryVec", vecLiteral)
                    .param("alpha", props.alpha())
                    .param("threshold", props.similarityThreshold())
                    .param("k", limit)
                    .paramSource(optionalParams)
                    .query(this::mapChunk)
                    .list();
            Instant end = Instant.now();
            metricsRecorder.recordRagSearchLatency(corpus, Duration.between(start, end));
            metricsRecorder.recordRagChunkCount(corpus, results.size());
            if (results.isEmpty()) {
                metricsRecorder.recordRagSearchMiss(corpus);
            }
            if (langfuse != null) {
                String traceId = langfuse.newTraceId();
                langfuse.trace(traceId, "auto-loan-review", null, List.of("auto-loan-review"));
                langfuse.span(traceId, "rag-search",
                        java.util.Map.of("corpus", corpus, "query", query),
                        java.util.Map.of("chunkCount", results.size()),
                        start, end);
            }
            return results;
        } catch (Exception e) {
            log.error("RagSearchService: 검색 실패 corpus={} query={}", corpus, query, e);
            metricsRecorder.recordRagSearchLatency(corpus, Duration.between(start, Instant.now()));
            metricsRecorder.recordRagSearchMiss(corpus);
            return List.of();
        }
    }

    /** chunk id 존재 여부 — GroundingValidator 에서 citation 검증 시 사용. */
    public boolean existsById(long id) {
        return Boolean.TRUE.equals(
                jdbcClient.sql("SELECT EXISTS(SELECT 1 FROM ai_embedding WHERE id = :id AND is_active)")
                        .param("id", id)
                        .query(Boolean.class)
                        .single());
    }

    /** sourceId 존재 여부 — RagPolicyIndex.exists() 에서 citation 검증 시 사용. */
    public boolean existsBySourceId(String corpus, String sourceId) {
        return Boolean.TRUE.equals(
                jdbcClient.sql("""
                        SELECT EXISTS(
                          SELECT 1 FROM ai_embedding
                          WHERE corpus = :corpus AND source_id = :sourceId AND is_active
                        )""")
                        .param("corpus", corpus)
                        .param("sourceId", sourceId)
                        .query(Boolean.class)
                        .single());
    }

    /** sourceId 로 단건 청크 조회 — RagPolicyIndex.get() 에서 사용. */
    public Optional<Chunk> findBySourceId(String corpus, String sourceId) {
        try {
            return jdbcClient.sql("""
                    SELECT id, source_id, chunk_text, chunk_summary, metadata::text,
                           1.0 AS hybrid_score
                    FROM ai_embedding
                    WHERE corpus = :corpus AND source_id = :sourceId AND is_active
                    LIMIT 1
                    """)
                    .param("corpus", corpus)
                    .param("sourceId", sourceId)
                    .query(this::mapChunk)
                    .optional();
        } catch (Exception e) {
            log.error("RagSearchService: findBySourceId 실패 corpus={} sourceId={}", corpus, sourceId, e);
            return Optional.empty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────

    private static String buildSql(boolean withMetaFilter, boolean withFts) {
        String metaCond = withMetaFilter
                ? "AND metadata @> :metaFilter::jsonb" : "";

        if (withFts) {
            return """
                    WITH vec AS (
                        SELECT id, source_id, chunk_text, chunk_summary, metadata,
                               1 - (embedding <=> CAST(:queryVec AS vector)) AS vec_score
                        FROM ai_embedding
                        WHERE corpus = :corpus
                          AND is_active
                          %s
                        ORDER BY embedding <=> CAST(:queryVec AS vector)
                        LIMIT 50
                    ),
                    fts AS (
                        SELECT id,
                               ts_rank_cd(fts_tokens, to_tsquery('simple', :queryTsq)) AS fts_score
                        FROM ai_embedding
                        WHERE corpus = :corpus
                          AND is_active
                          AND fts_tokens @@ to_tsquery('simple', :queryTsq)
                        LIMIT 50
                    )
                    SELECT v.id, v.source_id, v.chunk_text, v.chunk_summary, v.metadata::text,
                           (:alpha * v.vec_score + (1 - :alpha) * COALESCE(f.fts_score, 0)) AS hybrid_score
                    FROM vec v
                    LEFT JOIN fts f USING (id)
                    WHERE (:alpha * v.vec_score + (1 - :alpha) * COALESCE(f.fts_score, 0)) >= :threshold
                    ORDER BY hybrid_score DESC
                    LIMIT :k
                    """.formatted(metaCond);
        }

        // FTS 스킵 — 특수문자 전용 쿼리 등 tsquery 생성 불가 시 벡터 검색만 수행
        return """
                SELECT id, source_id, chunk_text, chunk_summary, metadata::text,
                       1 - (embedding <=> CAST(:queryVec AS vector)) AS hybrid_score
                FROM ai_embedding
                WHERE corpus = :corpus
                  AND is_active
                  %s
                  AND (1 - (embedding <=> CAST(:queryVec AS vector))) >= :threshold
                ORDER BY hybrid_score DESC
                LIMIT :k
                """.formatted(metaCond);
    }

    @SuppressWarnings("unchecked")
    private Chunk mapChunk(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        long id = rs.getLong("id");
        String sourceId = rs.getString("source_id");
        String text = rs.getString("chunk_text");
        String summary = rs.getString("chunk_summary");
        String metaJson = rs.getString("metadata");
        double score = rs.getDouble("hybrid_score");

        Map<String, Object> metadata = parseJsonb(metaJson);
        return new Chunk(id, null, sourceId, text, summary, metadata, score);
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

    /**
     * 자연어 쿼리를 PostgreSQL simple tsquery 토큰으로 변환.
     * 특수문자만 있는 경우 등 유효 토큰이 없으면 {@code null} 반환 → FTS 스킵.
     */
    private static String toTsQuery(String query) {
        String processed = query.trim()
                .replaceAll("\\s+", " & ")
                .replaceAll("[^가-힣a-zA-Z0-9&_ ]", "")
                .trim();
        return processed.isBlank() ? null : processed;
    }

    /** JSONB 직렬화 — ObjectMapper 사용으로 특수문자 이스케이프 보장. */
    private static String toJsonb(Map<String, Object> filter) {
        try {
            return MAPPER.writeValueAsString(filter);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Map<String, Object> parseJsonb(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) return Map.of();
        try {
            return MAPPER.readValue(json, new TypeReference<HashMap<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
