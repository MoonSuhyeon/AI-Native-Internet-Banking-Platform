package com.bank.ai.rag.api;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.bank.ai.metrics.AgentMetricsRecorder;
import com.bank.ai.rag.api.dto.ChunkBatchItem;
import com.bank.ai.rag.embedding.EmbeddingClient;
import com.bank.ai.rag.embedding.StubEmbeddingClient;
import com.bank.ai.rag.es.config.EsProperties;
import com.bank.ai.rag.es.index.EsIndexAdminService;
import com.bank.ai.rag.es.search.EsHybridSearchService;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EsCorpusIndexer testcontainers 통합 테스트 — Phase E (E3-2).
 *
 * <p>FAQ 청크를 {@code kb_internal_faq} 에 적재한 뒤 {@link EsHybridSearchService} 로
 * 조회되는지(적재→검색 end-to-end) 검증한다.
 */
@Testcontainers
class EsCorpusIndexerTest {

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

    private static final String CORPUS = "internal_faq";

    private static ElasticsearchClient esClient;
    private static EsCorpusIndexer indexer;
    private static EsHybridSearchService searchService;
    private static final EmbeddingClient embeddingClient = new StubEmbeddingClient();

    @BeforeAll
    static void setUp() throws IOException {
        String uri = "http://" + esContainer.getHttpHostAddress();
        RestClient restClient = RestClient.builder(HttpHost.create(uri)).build();
        esClient = new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));

        new EsIndexAdminService(esClient).createIndex("kb_internal_faq_v1", "es/mappings/kb_internal_faq_v1.json");
        esClient.indices().putAlias(r -> r.index("kb_internal_faq_v1").name("kb_internal_faq"));

        EsProperties esProps = new EsProperties(
                uri, "", Duration.ofSeconds(2), Duration.ofSeconds(5),
                new EsProperties.EsIndexNames("kb_policy", "kb_similar_cases", "kb_internal_faq"),
                new EsProperties.EsSearchConfig(100, 50, 60));

        indexer = new EsCorpusIndexer(esClient, embeddingClient, esProps);
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
    void FAQ_적재_후_검색_조회() throws IOException {
        indexer.upsert(new ChunkBatchItem(
                CORPUS, "FAQ_DSR_001", 0,
                "DSR은 총부채원리금상환비율로 연소득 대비 연간 원리금 비중입니다",
                "DSR 정의", Map.of("category", "용어")));
        esClient.indices().refresh(r -> r.index("kb_internal_faq"));

        List<Chunk> results = searchService.search(CORPUS, "DSR 총부채원리금상환비율", null, 5);

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).sourceId()).isEqualTo("FAQ_DSR_001");
        assertThat(results.get(0).metadata().get("category")).isEqualTo("용어");
    }
}
