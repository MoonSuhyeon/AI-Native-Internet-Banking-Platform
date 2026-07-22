package com.bank.ai.rag.es.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Elasticsearch 연결·인덱스·검색 설정 — application.yml {@code ai.rag.es} 섹션.
 *
 * <p>Phase E (E1-5) 신규. {@code @ConfigurationPropertiesScan("com.bank.ai")} 로 자동 등록.
 *
 * @param uris           ES 클러스터 URI (단일 URI, 기본 http://localhost:9200)
 * @param apiKey         API 키 인증 (빈 문자열 = 인증 없음, dev 기본)
 * @param connectTimeout HTTP 연결 타임아웃
 * @param readTimeout    HTTP 읽기 타임아웃
 * @param indexes        코퍼스별 alias 이름
 * @param search         ES 검색 파라미터 (RRF, num_candidates)
 */
@ConfigurationProperties(prefix = "ai.rag.es")
public record EsProperties(
        @DefaultValue("http://localhost:9200") String uris,
        @DefaultValue("") String apiKey,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout,
        @DefaultValue EsIndexNames indexes,
        @DefaultValue EsSearchConfig search
) {

    /**
     * 코퍼스별 ES index alias 이름.
     *
     * @param policy 정책·규제 코퍼스 alias
     * @param cases  유사 케이스 코퍼스 alias
     * @param faq    내부 FAQ 코퍼스 alias
     */
    public record EsIndexNames(
            @DefaultValue("kb_policy") String policy,
            @DefaultValue("kb_similar_cases") String cases,
            @DefaultValue("kb_internal_faq") String faq
    ) {}

    /**
     * ES 하이브리드 검색 파라미터.
     *
     * @param numCandidates    kNN 후보 수 (HNSW 탐색 범위)
     * @param rrfRankWindowSize RRF rank_window_size
     * @param rrfRankConstant  RRF rank_constant (k)
     */
    public record EsSearchConfig(
            @DefaultValue("100") int numCandidates,
            @DefaultValue("50") int rrfRankWindowSize,
            @DefaultValue("60") int rrfRankConstant
    ) {}
}
