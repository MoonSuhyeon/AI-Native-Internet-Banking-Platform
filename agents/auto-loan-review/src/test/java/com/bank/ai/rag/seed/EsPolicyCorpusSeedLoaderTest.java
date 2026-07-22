package com.bank.ai.rag.seed;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.bank.ai.llm.policy.InlinePolicyIndex;
import com.bank.ai.llm.policy.PolicyIndex;
import com.bank.ai.metrics.AgentMetricsRecorder;
import com.bank.ai.rag.RagProperties;
import com.bank.ai.rag.embedding.EmbeddingClient;
import com.bank.ai.rag.embedding.StubEmbeddingClient;
import com.bank.ai.rag.es.config.EsProperties;
import com.bank.ai.rag.es.index.EsIndexAdminService;
import com.bank.ai.rag.es.search.EsHybridSearchService;
import com.bank.ai.rag.search.Chunk;
import com.bank.ai.rag.search.RagSearchProperties;
import com.bank.ai.rule.config.RuleEngineProperties;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EsPolicyCorpusSeedLoader testcontainers 통합 테스트 — Phase E (E3-1) seed→search end-to-end.
 *
 * <p>공통 {@link PolicyCorpusChunkProvider} 청크를 ES {@code kb_policy} 에 적재한 뒤
 * {@link EsHybridSearchService} 로 검색해 정책이 조회되는지 검증한다.
 */
@Testcontainers
class EsPolicyCorpusSeedLoaderTest {

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

    private static final InlinePolicyIndex POLICY_INDEX = new InlinePolicyIndex(Map.of(
            "MORT_DSR_LIMIT_V1", new PolicyIndex.PolicyEntry("주택담보대출 DSR 40퍼센트 이하", "internal_policy"),
            "CRED_SCORE_MIN_V1", new PolicyIndex.PolicyEntry("최저 신용점수 600점", "internal_policy")
    ));

    private static final RuleEngineProperties RULE_ENGINE_PROPS = new RuleEngineProperties(
            new RuleEngineProperties.HardConstraints(0.40, 0.70, 600, 0, 19),
            0.30, 0.50,
            Map.of("MORT_001", Map.of("regular", 0.347, "young", 0.750, "senior", 0.788)),
            0.95, 0.20, true, false
    );

    private static ElasticsearchClient esClient;
    private static EsPolicyCorpusSeedLoader seedLoader;
    private static EsHybridSearchService searchService;
    private static final EmbeddingClient embeddingClient = new StubEmbeddingClient();

    @BeforeAll
    static void setUp() throws IOException {
        String uri = "http://" + esContainer.getHttpHostAddress();
        RestClient restClient = RestClient.builder(HttpHost.create(uri)).build();
        esClient = new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));

        new EsIndexAdminService(esClient).createIndex("kb_policy_v1", "es/mappings/kb_policy_v1.json");
        esClient.indices().putAlias(r -> r.index("kb_policy_v1").name("kb_policy"));

        EsProperties esProps = new EsProperties(
                uri, "", Duration.ofSeconds(2), Duration.ofSeconds(5),
                new EsProperties.EsIndexNames("kb_policy", "kb_similar_cases", "kb_internal_faq"),
                new EsProperties.EsSearchConfig(100, 50, 60));
        RagProperties ragProps = new RagProperties(true, "es", Map.of("TRACK_3", 5));
        var chunkProvider = new PolicyCorpusChunkProvider(POLICY_INDEX, RULE_ENGINE_PROPS);

        seedLoader = new EsPolicyCorpusSeedLoader(
                chunkProvider, esClient, embeddingClient, esProps, ragProps, Runnable::run);
        searchService = new EsHybridSearchService(
                esClient, embeddingClient, esProps,
                new RagSearchProperties(0.7, 0.5, 5),
                new AgentMetricsRecorder(new SimpleMeterRegistry()));
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (esClient != null) {
            esClient._transport().close();
        }
    }

    @Test
    void seed_후_정책_검색_조회() {
        int count = seedLoader.doSeed();

        // inline 2개 + MORT_001 matrix 3개(regular/young/senior) = 5
        assertThat(count).isEqualTo(5);

        List<Chunk> results = searchService.search(CORPUS, "주택담보대출 DSR 40퍼센트 이하", null, 5);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).sourceId()).isEqualTo("MORT_DSR_LIMIT_V1");
    }

    @Test
    void seed_매트릭스_셀_matrix_coord_문자열로_색인() {
        seedLoader.doSeed();

        var matrix = searchService.findBySourceId(CORPUS, "MATRIX_MORT_001_REGULAR");
        assertThat(matrix).isPresent();
        assertThat(matrix.get().metadata().get("matrix_coord")).isEqualTo("MORT_001/regular");
    }
}
