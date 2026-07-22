package com.bank.ai.rag.es.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.bank.ai.metrics.AgentMetricsRecorder;
import com.bank.ai.rag.embedding.StubEmbeddingClient;
import com.bank.ai.rag.es.config.EsProperties;
import com.bank.ai.rag.es.index.EsIndexAdminService;
import com.bank.ai.rag.search.RagSearchProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BM25-only / kNN-only / RRF hybrid 오프라인 비교 평가.
 *
 * <p>10개 정책 문서 + 12개 레이블 쿼리(exact/keyword/semantic 유형)로
 * MRR@5 · Recall@5 를 전략별로 측정한다.
 * StubEmbeddingClient(SHA-256→768d) 를 사용하므로:
 * <ul>
 *   <li>exact   — 쿼리=문서 텍스트 → kNN 완벽 히트, BM25 양호</li>
 *   <li>keyword — 키워드 중복, 문장 상이 → BM25 유리, kNN 불리</li>
 *   <li>semantic— 의미 일치, 표면 상이 → 둘 다 어려움 (실 임베딩 한계 시뮬)</li>
 * </ul>
 * RRF는 두 retriever 의 rank 를 합산하므로 최악 전략보다 나빠지지 않아야 한다.
 */
@Testcontainers
class HybridSearchOfflineEvalTest {

    @Container
    static ElasticsearchContainer esContainer = noriContainer();

    private static final String CORPUS     = "policy_regulation";
    private static final String INDEX_NAME = "kb_policy_v1";
    private static final String ALIAS      = "kb_policy";
    private static final int    K          = 5;

    private static ElasticsearchClient   esClient;
    private static EsHybridSearchService searchService;
    private static final StubEmbeddingClient embeddingClient = new StubEmbeddingClient();

    // ── 레이블 코퍼스: 10 docs ──────────────────────────────────────────────

    record LabeledDoc(String sourceId, String text) {}

    static final List<LabeledDoc> DOCS = List.of(
            new LabeledDoc("MORT_DSR_V1",   "주담대 DSR 한도는 신용정책서 §3.1.2 에 따라 40% 이하"),
            new LabeledDoc("MORT_LTV_V1",   "주담대 LTV 한도는 70% 생애최초 80%"),
            new LabeledDoc("CRED_SCORE_V1", "자행 정책 최저 신용점수 NICE 600 KCB 600"),
            new LabeledDoc("DELINQ_V1",     "24개월 내 진행중 연체 1건 이상 즉시 반려"),
            new LabeledDoc("AGE_V1",        "신청 자격 최소 연령 19세 민법 성인 기준"),
            new LabeledDoc("PD_MATRIX_V1",  "상품 세그먼트별 PD 임계치 신용정책위원회 분기 의결"),
            new LabeledDoc("DECISION_V1",   "decision 모델 신뢰도 0.95 이상 자동 승인 권고"),
            new LabeledDoc("GOVERN_V1",     "ML 모델 변별력 산출 의사결정 정책 매트릭스 로직"),
            new LabeledDoc("MORT_REG_PD",   "MORT_001 regular 세그먼트 PD 임계치 0.347"),
            new LabeledDoc("MORT_YNG_PD",   "MORT_001 young 세그먼트 PD 임계치 0.750")
    );

    // ── 레이블 쿼리셋: 12 queries ───────────────────────────────────────────

    record LabeledQuery(String query, String expectedId, String type) {}

    static final List<LabeledQuery> QUERIES = List.of(
            // exact: 문서 원문과 동일 → StubEmbedding kNN 완벽 히트
            new LabeledQuery("주담대 DSR 한도는 신용정책서 §3.1.2 에 따라 40% 이하", "MORT_DSR_V1",   "exact"),
            new LabeledQuery("신청 자격 최소 연령 19세 민법 성인 기준",              "AGE_V1",        "exact"),
            // keyword: 핵심 어절 일치, 문장은 다름 → BM25 유리
            new LabeledQuery("DSR 40% 한도 주담대",                               "MORT_DSR_V1",   "keyword"),
            new LabeledQuery("신용점수 NICE 600",                                  "CRED_SCORE_V1", "keyword"),
            new LabeledQuery("MORT_001 regular 0.347",                            "MORT_REG_PD",   "keyword"),
            new LabeledQuery("연체 반려 24개월",                                   "DELINQ_V1",     "keyword"),
            // semantic: 의미 일치, 표면 어휘 상이 → 실 임베딩 없이 둘 다 어려움
            new LabeledQuery("소득 대비 부채 상환 비율 규정",                       "MORT_DSR_V1",   "semantic"),
            new LabeledQuery("담보 가치 대비 대출 상한 기준",                       "MORT_LTV_V1",   "semantic"),
            new LabeledQuery("채무 불이행 이력 심사 탈락",                          "DELINQ_V1",     "semantic"),
            new LabeledQuery("자동심사 모델 의사결정 한계",                          "GOVERN_V1",     "semantic"),
            new LabeledQuery("청년 세그먼트 부도 확률 상한",                         "MORT_YNG_PD",   "semantic"),
            new LabeledQuery("자동 승인 신뢰도 임계값",                             "DECISION_V1",   "semantic")
    );

    // ── 평가 결과 레코드 ────────────────────────────────────────────────────

    record QueryResult(LabeledQuery query, int bm25Rank, int knnRank, int rrfRank) {
        static int rankOf(List<String> ids, String target) {
            int idx = ids.indexOf(target);
            return idx >= 0 ? idx + 1 : 0; // 1-indexed, 0 = miss
        }
    }

    // ── 컨테이너·서비스 초기화 ──────────────────────────────────────────────

    @BeforeAll
    static void setUp() throws IOException {
        String uri = "http://" + esContainer.getHttpHostAddress();
        RestClient restClient = RestClient.builder(HttpHost.create(uri)).build();
        esClient = new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));

        new EsIndexAdminService(esClient).createIndex(INDEX_NAME, "es/mappings/kb_policy_v1.json");
        esClient.indices().putAlias(r -> r.index(INDEX_NAME).name(ALIAS));

        for (LabeledDoc doc : DOCS) {
            indexDoc(doc.sourceId(), doc.text());
        }
        esClient.indices().refresh(r -> r.index(INDEX_NAME));

        EsProperties esProps = new EsProperties(
                uri, "", Duration.ofSeconds(2), Duration.ofSeconds(5),
                new EsProperties.EsIndexNames(ALIAS, "kb_similar_cases", "kb_internal_faq"),
                new EsProperties.EsSearchConfig(100, 50, 60));
        searchService = new EsHybridSearchService(
                esClient, embeddingClient, esProps,
                new RagSearchProperties(0.7, 0.5, K),
                new AgentMetricsRecorder(new SimpleMeterRegistry()));
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (esClient != null) esClient._transport().close();
    }

    // ── 평가 메인 테스트 ────────────────────────────────────────────────────

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void 전략별_MRR_Recall_비교_평가() throws IOException {
        List<QueryResult> results = new ArrayList<>();

        for (LabeledQuery q : QUERIES) {
            List<String> bm25Ids = searchBm25(q.query());
            List<String> knnIds  = searchKnn(q.query());
            List<String> rrfIds  = searchService.search(CORPUS, q.query(), null, K)
                    .stream().map(c -> c.sourceId()).toList();

            results.add(new QueryResult(q,
                    QueryResult.rankOf(bm25Ids, q.expectedId()),
                    QueryResult.rankOf(knnIds,  q.expectedId()),
                    QueryResult.rankOf(rrfIds,  q.expectedId())));
        }

        // ── 메트릭 계산 ───────────────────────────────────────────────────

        double bm25Mrr = mrr(results, r -> r.bm25Rank());
        double knnMrr  = mrr(results, r -> r.knnRank());
        double rrfMrr  = mrr(results, r -> r.rrfRank());

        double bm25Recall = recall(results, r -> r.bm25Rank());
        double knnRecall  = recall(results, r -> r.knnRank());
        double rrfRecall  = recall(results, r -> r.rrfRank());

        // ── 유형별 MRR ────────────────────────────────────────────────────

        double bm25ExactMrr    = mrrByType(results, "exact",    r -> r.bm25Rank());
        double knnExactMrr     = mrrByType(results, "exact",    r -> r.knnRank());
        double rrfExactMrr     = mrrByType(results, "exact",    r -> r.rrfRank());
        double bm25KeyMrr      = mrrByType(results, "keyword",  r -> r.bm25Rank());
        double knnKeyMrr       = mrrByType(results, "keyword",  r -> r.knnRank());
        double rrfKeyMrr       = mrrByType(results, "keyword",  r -> r.rrfRank());
        double bm25SemMrr      = mrrByType(results, "semantic", r -> r.bm25Rank());
        double knnSemMrr       = mrrByType(results, "semantic", r -> r.knnRank());
        double rrfSemMrr       = mrrByType(results, "semantic", r -> r.rrfRank());

        // ── 결과 출력 ─────────────────────────────────────────────────────

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  하이브리드 검색 오프라인 평가 결과 (k=5, docs=10, queries=12)");
        System.out.println("  주의: StubEmbeddingClient 사용 — semantic 쿼리는 실 임베딩 아님");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.printf("  %-14s  %-10s  %-12s%n", "전략", "MRR@5", "Recall@5");
        System.out.println("  ─────────────────────────────────────────");
        System.out.printf("  %-14s  %-10.3f  %-12.3f%n", "BM25-only",  bm25Mrr, bm25Recall);
        System.out.printf("  %-14s  %-10.3f  %-12.3f%n", "kNN-only",   knnMrr,  knnRecall);
        System.out.printf("  %-14s  %-10.3f  %-12.3f%n", "RRF-hybrid", rrfMrr,  rrfRecall);
        System.out.println();
        System.out.printf("  %-14s  %-10s  %-10s  %-10s%n", "유형", "BM25", "kNN", "RRF");
        System.out.println("  ─────────────────────────────────────────");
        System.out.printf("  %-14s  %-10.3f  %-10.3f  %-10.3f%n", "exact(2)",    bm25ExactMrr, knnExactMrr, rrfExactMrr);
        System.out.printf("  %-14s  %-10.3f  %-10.3f  %-10.3f%n", "keyword(4)",  bm25KeyMrr,   knnKeyMrr,   rrfKeyMrr);
        System.out.printf("  %-14s  %-10.3f  %-10.3f  %-10.3f%n", "semantic(6)", bm25SemMrr,   knnSemMrr,   rrfSemMrr);
        System.out.println();
        System.out.printf("  %-42s  BM25  kNN  RRF%n", "쿼리 (유형)");
        System.out.println("  ─────────────────────────────────────────────────────────");
        for (QueryResult r : results) {
            String shortQ = r.query().query().length() > 32
                    ? r.query().query().substring(0, 30) + "…" : r.query().query();
            System.out.printf("  %-42s  %-5s %-5s %-5s%n",
                    "[" + r.query().type() + "] " + shortQ,
                    rankStr(r.bm25Rank()), rankStr(r.knnRank()), rankStr(r.rrfRank()));
        }
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();

        // ── 어설션: RRF 는 최악 단일 전략보다 크게 나빠지지 않아야 함 ─────

        double minBaseline = Math.min(bm25Mrr, knnMrr);
        assertThat(rrfMrr)
                .as("RRF MRR@5 이 단일 최악 전략 대비 20%% 이상 하락하지 않아야 함")
                .isGreaterThanOrEqualTo(minBaseline * 0.80);

        // exact 쿼리는 RRF 가 반드시 찾아야 함 (kNN rank-1 → RRF 도 top-3 이내)
        results.stream()
                .filter(r -> "exact".equals(r.query().type()))
                .forEach(r -> assertThat(r.rrfRank())
                        .as("exact 쿼리 [%s] RRF top-3", r.query().query())
                        .isGreaterThan(0)
                        .isLessThanOrEqualTo(3));
    }

    // ── 검색 헬퍼 ──────────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<String> searchBm25(String query) throws IOException {
        SearchRequest req = SearchRequest.of(s -> s
                .index(ALIAS).size(K)
                .retriever(r -> r.standard(std -> std
                        .query(q -> q.multiMatch(mm -> mm
                                .query(query)
                                .fields("chunk_text^2", "chunk_summary"))))));
        return extractSourceIds(esClient.search(req, Map.class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<String> searchKnn(String query) throws IOException {
        List<Float> queryVector = toFloatList(embeddingClient.embed(query));
        SearchRequest req = SearchRequest.of(s -> s
                .index(ALIAS).size(K)
                .retriever(r -> r.knn(knn -> knn
                        .field("embedding")
                        .queryVector(queryVector)
                        .k(K)
                        .numCandidates(100))));
        return extractSourceIds(esClient.search(req, Map.class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<String> extractSourceIds(
            co.elastic.clients.elasticsearch.core.SearchResponse<Map> resp) {
        return resp.hits().hits().stream()
                .map(h -> h.source() != null ? String.valueOf(h.source().get("source_id")) : "")
                .toList();
    }

    // ── 인덱싱 헬퍼 ────────────────────────────────────────────────────────

    private static void indexDoc(String sourceId, String text) throws IOException {
        List<Float> embedding = toFloatList(embeddingClient.embed(text));
        Map<String, Object> doc = Map.of(
                "corpus",        CORPUS,
                "source_id",     sourceId,
                "chunk_seq",     0,
                "chunk_text",    text,
                "chunk_summary", text,
                "metadata",      Map.of(),
                "embedding",     embedding);
        esClient.index(i -> i.index(INDEX_NAME).id(sourceId).document(doc));
    }

    private static List<Float> toFloatList(float[] vec) {
        List<Float> list = new ArrayList<>(vec.length);
        for (float v : vec) list.add(v);
        return list;
    }

    // ── 메트릭 헬퍼 ────────────────────────────────────────────────────────

    @FunctionalInterface
    interface RankExtractor { int rank(QueryResult r); }

    private static double mrr(List<QueryResult> results, RankExtractor fn) {
        return results.stream()
                .mapToDouble(r -> fn.rank(r) > 0 ? 1.0 / fn.rank(r) : 0.0)
                .average().orElse(0.0);
    }

    private static double recall(List<QueryResult> results, RankExtractor fn) {
        long hits = results.stream().filter(r -> fn.rank(r) > 0).count();
        return (double) hits / results.size();
    }

    private static double mrrByType(List<QueryResult> results, String type, RankExtractor fn) {
        var filtered = results.stream().filter(r -> type.equals(r.query().type())).toList();
        if (filtered.isEmpty()) return 0.0;
        return filtered.stream()
                .mapToDouble(r -> fn.rank(r) > 0 ? 1.0 / fn.rank(r) : 0.0)
                .average().orElse(0.0);
    }

    private static String rankStr(int rank) {
        return rank > 0 ? "@" + rank : "miss";
    }

    // ── Testcontainers 컨테이너 (nori 플러그인 + trial 라이선스) ────────────

    private static ElasticsearchContainer noriContainer() {
        var image = new ImageFromDockerfile("es-nori-eval:8.15.0", false)
                .withDockerfileFromBuilder(b -> b
                        .from("docker.elastic.co/elasticsearch/elasticsearch:8.15.0")
                        .run("bin/elasticsearch-plugin install --batch analysis-nori")
                        .build());
        return new ElasticsearchContainer(
                DockerImageName.parse(image.get())
                        .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch"))
                .withEnv("xpack.security.enabled", "false")
                .withEnv("xpack.license.self_generated.type", "trial")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")   // Docker Desktop 메모리 제약 — ES OOM(exit 137) 방지
                .withStartupTimeout(Duration.ofMinutes(5));
    }
}
