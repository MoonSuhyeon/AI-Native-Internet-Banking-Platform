package com.bank.ai.rag.es.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.bank.ai.metrics.AgentMetricsRecorder;
import com.bank.ai.rag.embedding.EmbeddingClient;
import com.bank.ai.rag.embedding.StubEmbeddingClient;
import com.bank.ai.rag.es.config.EsProperties;
import com.bank.ai.rag.es.index.EsIndexAdminService;
import com.bank.ai.rag.search.Chunk;
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
 * EsHybridSearchService testcontainers 통합 테스트 — Phase E (E2-1) 게이트.
 *
 * <p>kb_policy 인덱스에 정책 청크를 색인한 뒤 BM25+kNN RRF 검색이
 * 질의와 일치하는 청크를 top-1 으로 반환하는지, metaFilter 가 양쪽 retriever 에
 * 동일 적용되어 결과를 좁히는지, 단건 조회·빈 결과 처리를 검증한다.
 *
 * <p>ES {@code rrf} retriever 는 basic 라이선스에서 차단되므로 컨테이너에
 * trial 라이선스를 활성화한다. 또한 nori 형태소 매핑을 위해 {@code analysis-nori}
 * 플러그인을 설치한 커스텀 이미지를 빌드해 사용한다 (기본 이미지엔 미포함).
 */
@Testcontainers
class EsHybridSearchServiceTest {

    @Container
    static ElasticsearchContainer esContainer = noriContainer();

    private static ElasticsearchContainer noriContainer() {
        var image = new ImageFromDockerfile("es-nori-test:8.15.0", false)
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

    private static final String CORPUS = "policy_regulation";
    private static final String INDEX = "kb_policy_v1";

    private static final String DSR_TEXT = "주택담보대출 DSR 한도는 40퍼센트 입니다";
    private static final String CREDIT_TEXT = "신용대출 한도 산정 기준 안내";
    private static final String JEONSE_TEXT = "전세자금대출 LTV 규정 설명";

    private static ElasticsearchClient esClient;
    private static EsHybridSearchService searchService;
    private static final EmbeddingClient embeddingClient = new StubEmbeddingClient();

    @BeforeAll
    static void setUp() throws IOException {
        String uri = "http://" + esContainer.getHttpHostAddress();
        RestClient restClient = RestClient.builder(HttpHost.create(uri)).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        esClient = new ElasticsearchClient(transport);

        new EsIndexAdminService(esClient).createIndex(INDEX, "es/mappings/kb_policy_v1.json");
        esClient.indices().putAlias(r -> r.index(INDEX).name("kb_policy"));

        indexDoc("MORT_DSR_LIMIT_V1", DSR_TEXT, "MORTGAGE");
        indexDoc("CREDIT_LIMIT_V1", CREDIT_TEXT, "CREDIT");
        indexDoc("JEONSE_LTV_V1", JEONSE_TEXT, "JEONSE");
        esClient.indices().refresh(r -> r.index(INDEX));

        EsProperties esProps = new EsProperties(
                uri, "", Duration.ofSeconds(2), Duration.ofSeconds(5),
                new EsProperties.EsIndexNames("kb_policy", "kb_similar_cases", "kb_internal_faq"),
                new EsProperties.EsSearchConfig(100, 50, 60));
        RagSearchProperties searchProps = new RagSearchProperties(0.7, 0.5, 5);

        searchService = new EsHybridSearchService(
                esClient, embeddingClient, esProps, searchProps,
                new AgentMetricsRecorder(new SimpleMeterRegistry()));
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (esClient != null) {
            esClient._transport().close();
        }
    }

    @Test
    void RRF_검색_질의와_일치하는_청크를_top1_반환() {
        List<Chunk> results = searchService.search(CORPUS, DSR_TEXT, null, 5);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).sourceId()).isEqualTo("MORT_DSR_LIMIT_V1");
        assertThat(results.get(0).text()).isEqualTo(DSR_TEXT);
        assertThat(results.get(0).hybridScore()).isGreaterThan(0.0);
    }

    @Test
    void metaFilter_가_양쪽_retriever_에_적용되어_결과를_좁힌다() {
        // DSR 텍스트로 질의하지만 CREDIT 상품으로 필터 → DSR 문서는 제외되어야 함
        List<Chunk> results = searchService.search(
                CORPUS, DSR_TEXT, Map.of("product_code", "CREDIT"), 5);

        assertThat(results).isNotEmpty();
        assertThat(results).allSatisfy(c ->
                assertThat(c.metadata().get("product_code")).isEqualTo("CREDIT"));
        assertThat(results).noneSatisfy(c ->
                assertThat(c.sourceId()).isEqualTo("MORT_DSR_LIMIT_V1"));
    }

    @Test
    void metaFilter_일치_문서_없으면_빈_리스트() {
        List<Chunk> results = searchService.search(
                CORPUS, DSR_TEXT, Map.of("product_code", "NONEXISTENT"), 5);

        assertThat(results).isEmpty();
    }

    @Test
    void findBySourceId_단건_조회() {
        assertThat(searchService.existsBySourceId(CORPUS, "JEONSE_LTV_V1")).isTrue();
        assertThat(searchService.findBySourceId(CORPUS, "JEONSE_LTV_V1"))
                .hasValueSatisfying(c -> assertThat(c.text()).isEqualTo(JEONSE_TEXT));
    }

    @Test
    void findBySourceId_없는_id_는_empty() {
        assertThat(searchService.existsBySourceId(CORPUS, "NO_SUCH_ID")).isFalse();
        assertThat(searchService.findBySourceId(CORPUS, "NO_SUCH_ID")).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────

    private static void indexDoc(String sourceId, String text, String productCode) throws IOException {
        List<Float> embedding = toFloatList(embeddingClient.embed(text));
        Map<String, Object> doc = Map.of(
                "corpus", CORPUS,
                "source_id", sourceId,
                "chunk_seq", 0,
                "chunk_text", text,
                "chunk_summary", text,
                "metadata", Map.of("product_code", productCode),
                "embedding", embedding);
        esClient.index(i -> i.index(INDEX).id(sourceId).document(doc));
    }

    private static List<Float> toFloatList(float[] vec) {
        List<Float> list = new ArrayList<>(vec.length);
        for (float v : vec) list.add(v);
        return list;
    }
}
