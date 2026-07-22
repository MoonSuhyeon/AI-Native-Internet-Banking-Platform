package com.bank.ai.integration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.bank.ai.review.client.LoanServiceClient;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.AutoReviewResponse;
import com.bank.ai.review.dto.ReviewReportUpdateRequest;
import com.bank.ai.review.service.AutoReviewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ES 다운 시 fallback E2E 스모크 테스트 — phase-e-elasticsearch.md E4-3 (TC6).
 *
 * <p>{@code ElasticsearchClient.search()} 에서 IOException 이 발생할 때
 * {@code EsHybridSearchService} 가 예외를 내부에서 흡수하고 빈 리스트를 반환한 후,
 * 상위 파이프라인이 인라인 정책으로 보고서를 생성하여 DONE 콜백을 전송하는지 검증.
 *
 * <p>인프라 스터빙:
 * <ul>
 *   <li>LLM — {@code ai.llm.provider=stub}</li>
 *   <li>inference-server — {@code @MockBean AutoReviewService}</li>
 *   <li>loan-service 콜백 — {@code @MockBean LoanServiceClient}</li>
 *   <li>ES 클라이언트 — {@code @MockBean ElasticsearchClient}, search() 에서 IOException 발생</li>
 * </ul>
 *
 * <p>EsHybridSearchService·RagRetrievalService 는 실제 빈 사용 — fallback 경로 검증.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ai.llm.provider=stub",
                "ai.rag.enabled=true",
                "ai.rag.backend=es",
                "spring.datasource.url=jdbc:h2:mem:esdowndb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=VALUE,YEAR",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.flyway.locations=classpath:db/h2-migration",
                "spring.autoconfigure.exclude=" +
                        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration," +
                        "org.springframework.ai.model.vertexai.autoconfigure.embedding.VertexAiTextEmbeddingAutoConfiguration"
        }
)
class EsDownFallbackSmokeTest {

    @MockBean
    private AutoReviewService autoReviewService;

    @MockBean
    private LoanServiceClient loanServiceClient;

    /** ES 다운 시뮬레이션 — search() 호출 시 IOException 발생. EsClientConfig @Bean 대체. */
    @MockBean
    private ElasticsearchClient elasticsearchClient;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    @Qualifier("llmExecutor")
    private ThreadPoolTaskExecutor llmExecutor;

    private static final AutoReviewResponse TRACK1_INFERENCE = new AutoReviewResponse(
            "hmda_v1", "APPROVE", 0.97,
            Map.of("APPROVE", 0.97, "REJECT", 0.03), null, null
    );

    @BeforeEach
    void setUp() throws IOException {
        when(autoReviewService.review(any())).thenReturn(TRACK1_INFERENCE);
        doThrow(new IOException("Connection refused — ES 다운 시뮬레이션"))
                .when(elasticsearchClient).search(any(co.elastic.clients.elasticsearch.core.SearchRequest.class), any());
    }

    @AfterEach
    void drainAsyncTasks() {
        await().atMost(5, TimeUnit.SECONDS)
               .until(() -> llmExecutor.getThreadPoolExecutor().getActiveCount() == 0
                         && llmExecutor.getThreadPoolExecutor().getQueue().isEmpty());
    }

    // ── TC 6: ES 다운 → EsHybridSearchService fallback → DONE 콜백 ─────────

    @Test
    void ES_다운_시_빈_청크_반환_후_인라인_정책_fallback_DONE_콜백() {
        restTemplate.postForEntity("/api/ai/auto-review/evaluate",
                track1Request(206L), String.class);

        var captor = ArgumentCaptor.forClass(ReviewReportUpdateRequest.class);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(loanServiceClient).updateReport(eq(206L), captor.capture()));

        ReviewReportUpdateRequest req = captor.getValue();
        assertThat(req.status()).isEqualTo("DONE");
        assertThat(req.agentOpinionJson()).isNotNull().contains("schema_version");
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    private static AutoReviewRequest track1Request(Long revId) {
        return new AutoReviewRequest(
                revId, null, 35, null, null, null, null, null, null, null, null, null, "regular",
                null, null, null, null, null, null,
                0.30, 0.50, null, null, 0, 750,
                "MORT_001", 200_000_000L, 360, "아파트 구입 자금 대출", null,
                null, null, null, null, null, null, null
        );
    }
}
