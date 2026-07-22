package com.bank.ai.rag.es.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Retriever;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.bank.ai.metrics.AgentMetricsRecorder;
import com.bank.ai.rag.embedding.EmbeddingClient;
import com.bank.ai.rag.es.config.EsProperties;
import com.bank.ai.rag.search.Chunk;
import com.bank.ai.rag.search.RagSearchBackend;
import com.bank.ai.rag.search.RagSearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Elasticsearch 하이브리드 검색 서비스 — Phase E (E2-1).
 *
 * <p>BM25 (nori 형태소 + multi_match) retriever 와 dense_vector kNN retriever 를
 * ES {@code retriever} API 의 RRF 로 결합. 점수 정규화 없이 rank 기반 융합이라
 * BM25·cosine 스케일 차이를 신경 쓸 필요가 없다.
 *
 * <p>{@code metaFilter} 는 {@code bool.filter} 동등 의미로 BM25·kNN 양쪽 retriever 에
 * 동일 주입되어, 두 검색 경로가 같은 후보 집합 위에서 동작하도록 보장한다.
 *
 * <p>{@code ai.rag.backend=es} 시에만 활성화. 검색 실패 시 빈 리스트를 반환하여
 * 상위 {@code RagRetrievalService} 가 grounding 부족 케이스로 처리하도록 한다 (plan §7).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.rag", name = "backend", havingValue = "es")
public class EsHybridSearchService implements RagSearchBackend {

    private final ElasticsearchClient esClient;
    private final EmbeddingClient embeddingClient;
    private final EsProperties esProps;
    private final RagSearchProperties searchProps;
    private final AgentMetricsRecorder metricsRecorder;

    /**
     * 하이브리드 검색 (BM25 + kNN → RRF).
     *
     * @param corpus     코퍼스 식별자 (alias 로 매핑)
     * @param query      자연어 질의
     * @param metaFilter metadata 동등 필터 (null/빈 맵 = 필터 없음)
     * @param k          반환 건수 (0 이하 시 defaultK)
     * @return RRF 점수 내림차순 Chunk 목록. 실패 시 빈 리스트.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Chunk> search(String corpus, String query,
                              Map<String, Object> metaFilter, int k) {
        int limit = k > 0 ? k : searchProps.defaultK();
        String index = resolveAlias(corpus);
        List<Float> queryVector = toFloatList(embeddingClient.embed(query));
        List<Query> filters = buildFilters(metaFilter);
        EsProperties.EsSearchConfig sc = esProps.search();

        Instant start = Instant.now();
        try {
            SearchRequest req = SearchRequest.of(s -> s
                    .index(index)
                    .size(limit)
                    .retriever(r -> r.rrf(rrf -> rrf
                            .retrievers(
                                    bm25Retriever(query, filters),
                                    knnRetriever(queryVector, limit, sc.numCandidates(), filters))
                            .rankWindowSize(sc.rrfRankWindowSize())
                            .rankConstant(sc.rrfRankConstant()))));

            SearchResponse<Map> resp = esClient.search(req, Map.class);
            List<Chunk> results = new ArrayList<>();
            for (Hit<Map> hit : resp.hits().hits()) {
                results.add(toChunk(corpus, hit));
            }
            metricsRecorder.recordRagSearchLatency(corpus, "rrf", Duration.between(start, Instant.now()));
            metricsRecorder.recordRagChunkCount(corpus, results.size());
            if (results.isEmpty()) {
                metricsRecorder.recordRagSearchMiss(corpus);
            }
            return results;
        } catch (Exception e) {
            log.error("EsHybridSearchService: 검색 실패 corpus={} query={}", corpus, query, e);
            metricsRecorder.recordRagSearchLatency(corpus, "rrf", Duration.between(start, Instant.now()));
            metricsRecorder.recordRagSearchMiss(corpus);
            return List.of();
        }
    }

    /** sourceId 존재 여부 — GroundingValidator 의 {@code rag:} citation 검증용. */
    public boolean existsBySourceId(String corpus, String sourceId) {
        return findBySourceId(corpus, sourceId).isPresent();
    }

    /** sourceId 단건 조회 — term 필터로 첫 청크 반환. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Optional<Chunk> findBySourceId(String corpus, String sourceId) {
        String index = resolveAlias(corpus);
        try {
            SearchRequest req = SearchRequest.of(s -> s
                    .index(index)
                    .size(1)
                    .query(q -> q.term(t -> t.field("source_id").value(FieldValue.of(sourceId)))));
            SearchResponse<Map> resp = esClient.search(req, Map.class);
            List<Hit<Map>> hits = resp.hits().hits();
            if (hits.isEmpty()) return Optional.empty();
            return Optional.of(toChunk(corpus, hits.get(0)));
        } catch (Exception e) {
            log.error("EsHybridSearchService: findBySourceId 실패 corpus={} sourceId={}", corpus, sourceId, e);
            return Optional.empty();
        }
    }

    // ─────────────────────────────────────────────────────────────────────

    private Retriever bm25Retriever(String query, List<Query> filters) {
        return Retriever.of(r -> r.standard(std -> {
            std.query(q -> q.multiMatch(mm -> mm
                    .query(query)
                    .fields("chunk_text^2", "chunk_summary")));
            if (!filters.isEmpty()) std.filter(filters);
            return std;
        }));
    }

    private Retriever knnRetriever(List<Float> queryVector, int k,
                                   int numCandidates, List<Query> filters) {
        return Retriever.of(r -> r.knn(knn -> {
            knn.field("embedding")
                    .queryVector(queryVector)
                    .k(k)
                    .numCandidates(numCandidates);
            if (!filters.isEmpty()) knn.filter(filters);
            return knn;
        }));
    }

    /** metadata 동등 필터를 term 쿼리 목록으로 변환 (값은 문자열로 직렬화). */
    private static List<Query> buildFilters(Map<String, Object> metaFilter) {
        if (metaFilter == null || metaFilter.isEmpty()) return List.of();
        List<Query> filters = new ArrayList<>(metaFilter.size());
        metaFilter.forEach((key, value) -> filters.add(Query.of(q -> q.term(t -> t
                .field("metadata." + key)
                .value(FieldValue.of(String.valueOf(value)))))));
        return filters;
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

    @SuppressWarnings("unchecked")
    private static Chunk toChunk(String corpus, Hit<Map> hit) {
        Map<String, Object> src = hit.source() != null ? hit.source() : Map.of();
        Object metaObj = src.get("metadata");
        Map<String, Object> metadata = metaObj instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        double score = hit.score() != null ? hit.score() : 0.0;
        return new Chunk(
                0L,
                corpus,
                asString(src.get("source_id")),
                asString(src.get("chunk_text")),
                asString(src.get("chunk_summary")),
                metadata,
                score);
    }

    private static String asString(Object o) {
        return o != null ? o.toString() : null;
    }

    private static List<Float> toFloatList(float[] vec) {
        List<Float> list = new ArrayList<>(vec.length);
        for (float v : vec) list.add(v);
        return list;
    }
}
