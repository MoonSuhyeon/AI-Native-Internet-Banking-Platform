package com.bank.ai.rag.es.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EsClientConfig 단위 테스트 — E1-5 헬스체크.
 *
 * <p>실제 ES 연결 없이 {@link ElasticsearchClient} 빈이 생성되는지만 검증.
 * API 키 미설정·설정 두 케이스 모두 클라이언트가 null 이 아닌지 확인.
 */
class EsClientConfigTest {

    private final EsClientConfig config = new EsClientConfig();

    @Test
    void apiKey_없을때_클라이언트_생성_성공() {
        EsProperties props = new EsProperties(
                "http://localhost:9200",
                "",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                new EsProperties.EsIndexNames("kb_policy", "kb_similar_cases", "kb_internal_faq"),
                new EsProperties.EsSearchConfig(100, 50, 60)
        );

        ElasticsearchClient client = config.elasticsearchClient(props);
        assertThat(client).isNotNull();
    }

    @Test
    void apiKey_있을때_클라이언트_생성_성공() {
        EsProperties props = new EsProperties(
                "http://localhost:9200",
                "test-api-key-abc123",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                new EsProperties.EsIndexNames("kb_policy", "kb_similar_cases", "kb_internal_faq"),
                new EsProperties.EsSearchConfig(100, 50, 60)
        );

        ElasticsearchClient client = config.elasticsearchClient(props);
        assertThat(client).isNotNull();
    }

    @Test
    void EsProperties_기본값_확인() {
        EsProperties.EsIndexNames indexes = new EsProperties.EsIndexNames(
                "kb_policy", "kb_similar_cases", "kb_internal_faq");
        assertThat(indexes.policy()).isEqualTo("kb_policy");
        assertThat(indexes.cases()).isEqualTo("kb_similar_cases");
        assertThat(indexes.faq()).isEqualTo("kb_internal_faq");
    }

    @Test
    void EsSearchConfig_기본값_확인() {
        EsProperties.EsSearchConfig search = new EsProperties.EsSearchConfig(100, 50, 60);
        assertThat(search.numCandidates()).isEqualTo(100);
        assertThat(search.rrfRankWindowSize()).isEqualTo(50);
        assertThat(search.rrfRankConstant()).isEqualTo(60);
    }
}
