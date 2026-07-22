package com.bank.ai.integration;

import com.bank.ai.rag.seed.PolicyCorpusSeedLoader;
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

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

/**
 * ai.rag.enabled=false kill switch 검증 — RagPipelineSmokeTest 와 별도 컨텍스트.
 *
 * <p>RagRetrievalService 를 mock 하지 않고 실제 빈을 사용.
 * ragProps.enabled()==false 경로(즉시 빈 리스트 반환)가 실제로 동작하는지 확인.
 * RagSearchService 는 pgvector SQL 을 사용하므로 @MockBean 으로 격리.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ai.llm.provider=stub",
                "ai.rag.enabled=false",          // ← kill switch
                "spring.datasource.url=jdbc:h2:mem:ragkilldb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=VALUE,YEAR",
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
class RagKillSwitchTest {

    @MockBean
    private AutoReviewService autoReviewService;

    @MockBean
    private LoanServiceClient loanServiceClient;

    // RagRetrievalService 는 @MockBean 제외 — @ConditionalOnProperty 분기 실행 검증
    // RagSearchService 는 pgvector SQL 사용 불가(H2) → mock 으로 격리
    @MockBean
    private com.bank.ai.rag.search.RagSearchService ragSearchService;

    @MockBean
    private PolicyCorpusSeedLoader policySeedLoader;

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
    void setUp() {
        when(autoReviewService.review(any())).thenReturn(TRACK1_INFERENCE);
    }

    @AfterEach
    void drainAsyncTasks() {
        await().atMost(5, TimeUnit.SECONDS)
               .until(() -> llmExecutor.getThreadPoolExecutor().getActiveCount() == 0
                         && llmExecutor.getThreadPoolExecutor().getQueue().isEmpty());
    }

    // ── TC 5: ai.rag.enabled=false → RagRetrievalService.retrieve() 즉시 반환 ──

    @Test
    void RAG_킬스위치_비활성_인라인_정책_단독_경로_DONE_콜백() {
        // RagSearchService 가 mock 이지만 ragProps.enabled()==false 이면
        // RagRetrievalService 가 ragSearchService.search() 를 호출하지 않아야 함.
        restTemplate.postForEntity("/api/ai/auto-review/evaluate",
                request(301L), String.class);

        var captor = ArgumentCaptor.forClass(ReviewReportUpdateRequest.class);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(loanServiceClient).updateReport(eq(301L), captor.capture()));

        assertThat(captor.getValue().status()).isEqualTo("DONE");
        assertThat(captor.getValue().report()).isNotNull();

        // kill switch 동작 시 RagSearchService 는 단 한 번도 호출되지 않아야 함
        org.mockito.Mockito.verifyNoInteractions(ragSearchService);
    }

    private static AutoReviewRequest request(Long revId) {
        return new AutoReviewRequest(
                revId, null, 35, null, null, null, null, null, null, null, null, null, "regular",
                null, null, null, null, null, null,
                0.30, 0.50, null, null, 0, 750,
                "MORT_001", 200_000_000L, 360, "아파트 구입 자금 대출", null,
                null, null, null, null, null, null, null
        );
    }
}
