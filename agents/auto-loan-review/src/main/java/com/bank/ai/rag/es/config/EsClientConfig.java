package com.bank.ai.rag.es.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;

import java.util.List;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch Java 클라이언트 빈 설정 — Phase E (E1-5).
 *
 * <p>{@code ai.rag.backend=es} 시에만 활성화.
 * API 키가 설정된 경우 Authorization 헤더 자동 주입.
 * 타임아웃은 {@link EsProperties#connectTimeout()} / {@link EsProperties#readTimeout()} 참조.
 *
 * <p>JSON 매핑: {@link JacksonJsonpMapper} (classpath Jackson 재사용).
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "ai.rag", name = "backend", havingValue = "es")
@EnableConfigurationProperties(EsProperties.class)
public class EsClientConfig {

    @Bean
    public ElasticsearchClient elasticsearchClient(EsProperties esProps) {
        log.info("EsClientConfig: ElasticsearchClient 초기화 — uris={}", esProps.uris());

        RestClient restClient = RestClient
                .builder(HttpHost.create(esProps.uris()))
                .setRequestConfigCallback(rcb -> rcb
                        .setConnectTimeout((int) esProps.connectTimeout().toMillis())
                        .setSocketTimeout((int) esProps.readTimeout().toMillis()))
                .setHttpClientConfigCallback(hcb -> {
                    if (esProps.apiKey() != null && !esProps.apiKey().isBlank()) {
                        hcb.setDefaultHeaders(List.of(
                                new BasicHeader("Authorization", "ApiKey " + esProps.apiKey())
                        ));
                    }
                    return hcb;
                })
                .build();

        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }
}
