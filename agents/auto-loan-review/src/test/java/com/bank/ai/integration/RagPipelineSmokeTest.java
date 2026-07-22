package com.bank.ai.integration;

import com.bank.ai.rag.retrieval.RagRetrievalService;
import com.bank.ai.rag.search.Chunk;
import com.bank.ai.rag.seed.PolicyCorpusSeedLoader;
import com.bank.ai.review.client.LoanServiceClient;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.AutoReviewResponse;
import com.bank.ai.review.dto.ReviewReportUpdateRequest;
import com.bank.ai.review.service.AutoReviewService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.NestedTestConfiguration.EnclosingConfiguration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RAG 파이프라인 E2E 스모크 테스트 — phase-e-elasticsearch.md E4-3.
 *
 * <p>6 케이스: Track1/2/3 각 1 + RAG 검색 empty 1 + ai.rag.enabled=false fallback 1
 * (TC5 — {@link RagKillSwitchTest}) + ES 다운 fallback 1 ({@link EsDownFallbackSmokeTest}).
 *
 * <p>인프라 스터빙:
 * <ul>
 *   <li>LLM — {@code ai.llm.provider=stub}</li>
 *   <li>inference-server — {@code @MockBean AutoReviewService}</li>
 *   <li>loan-service 콜백 — {@code @MockBean LoanServiceClient}</li>
 *   <li>RAG 검색 — {@code @MockBean RagRetrievalService} (pgvector 없이 청크 주입)</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ai.llm.provider=stub",
                "ai.rag.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:ragsmokedb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=VALUE,YEAR",
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
class RagPipelineSmokeTest {

    @MockBean
    private AutoReviewService autoReviewService;

    @MockBean
    private LoanServiceClient loanServiceClient;

    @MockBean
    private RagRetrievalService ragRetrievalService;

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

    private static final AutoReviewResponse TRACK3_INFERENCE = new AutoReviewResponse(
            "hmda_v1", "APPROVE", 0.65,
            Map.of("APPROVE", 0.65, "REJECT", 0.35), 0.25, "pd_v1"
    );

    private static final AutoReviewResponse TRACK3_SIM = new AutoReviewResponse(
            "hmda_v1", "APPROVE", 0.72,
            Map.of("APPROVE", 0.72, "REJECT", 0.28), 0.20, "pd_v1"
    );

    private static final List<Chunk> RAG_CHUNKS = List.of(
            new Chunk(1L, "policy_regulation", "rag-policy-001",
                    "DSR 한도 정책 청크", null, Map.of(), 0.85),
            new Chunk(2L, "policy_regulation", "rag-policy-002",
                    "LTV 한도 정책 청크", null, Map.of(), 0.78)
    );

    @BeforeEach
    void setUp() {
        when(autoReviewService.review(any())).thenReturn(TRACK1_INFERENCE);
        when(ragRetrievalService.retrieve(any(), any(), any(), any(), any())).thenReturn(RAG_CHUNKS);
    }

    @AfterEach
    void drainAsyncTasks() {
        await().atMost(5, TimeUnit.SECONDS)
               .until(() -> llmExecutor.getThreadPoolExecutor().getActiveCount() == 0
                         && llmExecutor.getThreadPoolExecutor().getQueue().isEmpty());
    }

    // ── TC 1: Track 1 + RAG 활성 — DONE 콜백 ────────────────────────────

    @Test
    void Track1_RAG_활성_DONE_콜백() {
        restTemplate.postForEntity("/api/ai/auto-review/evaluate",
                track1Request(201L), String.class);

        var captor = ArgumentCaptor.forClass(ReviewReportUpdateRequest.class);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(loanServiceClient).updateReport(eq(201L), captor.capture()));

        ReviewReportUpdateRequest req = captor.getValue();
        assertThat(req.status()).isEqualTo("DONE");
        assertThat(req.report().track().name()).isEqualTo("TRACK_1");
        assertThat(req.agentOpinionJson()).isNotNull().contains("schema_version");
    }

    // ── TC 2: Track 2 + RAG 활성 — COMPLIANCE_REVIEW_REQUIRED ──────────

    @Test
    void Track2_RAG_활성_COMPLIANCE_REVIEW_REQUIRED_콜백() {
        restTemplate.postForEntity("/api/ai/auto-review/evaluate",
                track2Request(202L), String.class);

        var captor = ArgumentCaptor.forClass(ReviewReportUpdateRequest.class);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(loanServiceClient).updateReport(eq(202L), captor.capture()));

        ReviewReportUpdateRequest req = captor.getValue();
        assertThat(req.status()).isEqualTo("DONE");
        assertThat(req.report().track().name()).isEqualTo("TRACK_2");
        assertThat(req.agentOpinionJson()).contains("COMPLIANCE_REVIEW_REQUIRED");
    }

    // ── TC 3: Track 3 + RAG 활성 — MEDIUM 리스크 콜백 ──────────────────
    // TRACK_3 진입 조건: autoReviewService 가 mock 이므로 DSR/LTV 규칙이 아닌
    // inference 응답(TRACK3_INFERENCE: pd=0.25, confidence=0.65)이 TrackClassifier 에
    // 전달되어 회색지대 → TRACK_3 분류됨.

    @Test
    void Track3_RAG_활성_DONE_MEDIUM_콜백() {
        when(autoReviewService.review(any()))
                .thenReturn(TRACK3_INFERENCE)
                .thenReturn(TRACK3_SIM)
                .thenReturn(TRACK3_SIM);

        restTemplate.postForEntity("/api/ai/auto-review/evaluate",
                track3Request(203L), String.class);

        var captor = ArgumentCaptor.forClass(ReviewReportUpdateRequest.class);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(loanServiceClient).updateReport(eq(203L), captor.capture()));

        ReviewReportUpdateRequest req = captor.getValue();
        assertThat(req.status()).isEqualTo("DONE");
        assertThat(req.report().track().name()).isEqualTo("TRACK_3");
        assertThat(req.agentOpinionJson()).contains("MEDIUM");
    }

    // ── TC 4: RAG 검색 empty → 인라인 정책으로 fallback ─────────────────

    @Test
    void RAG_검색_empty_인라인_정책_fallback_DONE_콜백() {
        when(ragRetrievalService.retrieve(any(), any(), any(), any(), any())).thenReturn(List.of());

        restTemplate.postForEntity("/api/ai/auto-review/evaluate",
                track1Request(204L), String.class);

        var captor = ArgumentCaptor.forClass(ReviewReportUpdateRequest.class);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(loanServiceClient).updateReport(eq(204L), captor.capture()));

        ReviewReportUpdateRequest req = captor.getValue();
        assertThat(req.status()).isEqualTo("DONE");
        assertThat(req.agentOpinionJson()).isNotNull().contains("schema_version");
    }

    // ── TC 5: ai.rag.enabled=false 킬스위치 — 인라인 정책 fallback ─────────

    /**
     * RAG kill switch ({@code ai.rag.enabled=false}) 하에서도 인라인 정책으로
     * DONE 콜백이 전송되는지 검증. RagRetrievalService 는 실제 빈 사용.
     */
    @Nested
    @NestedTestConfiguration(EnclosingConfiguration.OVERRIDE)   // 바깥 @MockBean 상속 차단 — 중복 mock 정의 방지
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {
                    "ai.llm.provider=stub",
                    "ai.rag.enabled=false",
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

        @Autowired
        private TestRestTemplate restTemplate;

        @Autowired
        @Qualifier("llmExecutor")
        private ThreadPoolTaskExecutor llmExecutor;

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

        @Test
        void RAG_킬스위치_비활성_시_인라인_정책으로_DONE_콜백() {
            restTemplate.postForEntity("/api/ai/auto-review/evaluate",
                    track1Request(205L), String.class);

            var captor = ArgumentCaptor.forClass(ReviewReportUpdateRequest.class);
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                    verify(loanServiceClient).updateReport(eq(205L), captor.capture()));

            ReviewReportUpdateRequest req = captor.getValue();
            assertThat(req.status()).isEqualTo("DONE");
            assertThat(req.agentOpinionJson()).isNotNull().contains("schema_version");
        }
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

    private static AutoReviewRequest track2Request(Long revId) {
        return new AutoReviewRequest(
                revId, null, 35, null, null, null, null, null, null, null, null, null, "regular",
                null, null, null, null, null, null,
                0.55, 0.50, null, null, 0, 750,   // dsr=0.55 → DSR_EXCEEDED → TRACK_2
                "MORT_001", 200_000_000L, 360, "아파트 구입 자금 대출", null,
                null, null, null, null, null, null, null
        );
    }

    /**
     * Track 3 진입용 요청 픽스처.
     *
     * <p>dsr=0.30 으로 하드 제약을 통과하되, inference 응답(TRACK3_INFERENCE)의
     * pd=0.25·confidence=0.65 가 PolicyMatrix 회색지대에 해당하여 TRACK_3 로 분류됨.
     * Track 결정은 autoReviewService(mock)의 응답에서 비롯되므로 DSR 값과는 무관.
     */
    private static AutoReviewRequest track3Request(Long revId) {
        return new AutoReviewRequest(
                revId, null, 35, null, null, null, null, null, null, null, null, null, "regular",
                null, null, null, null, null, null,
                0.30, 0.50, null, null, 0, 690,   // dsr=0.30, credit=690 — 하드 제약 통과
                "MORT_001", 200_000_000L, 360, "아파트 구입 자금 대출", null,
                null, null, null, null, null, null, null
        );
    }
}
