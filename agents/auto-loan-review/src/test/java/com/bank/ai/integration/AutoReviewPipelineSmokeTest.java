package com.bank.ai.integration;

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
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

/**
 * LLM 파이프라인 E2E 스모크 테스트 — plan/llm-pipeline.md §11 (L11).
 *
 * <p>검증 흐름:
 * POST /api/ai/auto-review/evaluate
 *   → sync 즉시 응답 (PENDING)
 *   → @Async("llmExecutor") 비동기 파이프라인 실행
 *     (StubLlmClient: purpose_analysis + review_report_track1)
 *   → LoanServiceClient.updateReport(revId, DONE) 호출 검증
 *
 * <p>인프라 스터빙:
 * <ul>
 *   <li>LLM provider — {@code ai.llm.provider=stub} (StubLlmClient 자동 활성)</li>
 *   <li>inference-server — {@code @MockBean AutoReviewService} (Track 1 진입 조건)</li>
 *   <li>loan-service 콜백 — {@code @MockBean LoanServiceClient}
 *       (Mockito 검증, HTTP 레이어 제거 — wiremock-standalone 의 Jetty H2 클라이언트 간섭 방지)</li>
 *   <li>Redis — autoconfigure 제외</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "ai.llm.provider=stub",
                "spring.datasource.url=jdbc:h2:mem:smokedb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=VALUE,YEAR",
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
class AutoReviewPipelineSmokeTest {

    // ── Mocks ─────────────────────────────────────────────────────────────

    /** inference-server 대체 — 고신뢰 APPROVE 응답 (Track 1 진입 조건 충족). */
    @MockBean
    private AutoReviewService autoReviewService;

    /** loan-service 콜백 클라이언트 — Mockito 로 콜백 파라미터 검증. */
    @MockBean
    private LoanServiceClient loanServiceClient;

    @Autowired
    private TestRestTemplate restTemplate;

    /** 테스트 간 비동기 태스크 유출 방지 — llmExecutor 큐 드레인 대기. */
    @Autowired
    @Qualifier("llmExecutor")
    private ThreadPoolTaskExecutor llmExecutor;

    /** P(APPROVE)=0.97, pdScore=null → PD 폴백 pd=0.03 → Track 1. */
    private static final AutoReviewResponse TRACK1_INFERENCE = new AutoReviewResponse(
            "hmda_v1", "APPROVE", 0.97,
            Map.of("APPROVE", 0.97, "REJECT", 0.03),
            null, null
    );

    /**
     * pdScore=0.25 (회색지대: 0.1041 < 0.25 ≤ 0.347), decisionScore=0.65 (0.20 < 0.65 < 0.95) → Track 3.
     * MORT_001/regular 정책: pdThreshold=0.347, safetyTau=0.347×0.30=0.1041, decStrong=0.95, decReject=0.20.
     */
    private static final AutoReviewResponse TRACK3_INFERENCE = new AutoReviewResponse(
            "hmda_v1", "APPROVE", 0.65,
            Map.of("APPROVE", 0.65, "REJECT", 0.35),
            0.25, "pd_v1"
    );

    /** 시뮬레이션 개선 응답 — decision score +0.07 → toSimResult() risk_reduced 판정. */
    private static final AutoReviewResponse TRACK3_SIM_IMPROVED = new AutoReviewResponse(
            "hmda_v1", "APPROVE", 0.72,
            Map.of("APPROVE", 0.72, "REJECT", 0.28),
            0.20, "pd_v1"
    );

    @BeforeEach
    void setUp() {
        when(autoReviewService.review(any())).thenReturn(TRACK1_INFERENCE);
    }

    /**
     * 각 테스트 종료 후 llmExecutor 의 활성 스레드가 0이 될 때까지 대기.
     * 이전 테스트의 비동기 파이프라인이 다음 테스트로 유출되는 레이스 컨디션 방지.
     */
    @AfterEach
    void drainAsyncTasks() {
        await().atMost(5, TimeUnit.SECONDS)
               .until(() -> llmExecutor.getThreadPoolExecutor().getActiveCount() == 0
                         && llmExecutor.getThreadPoolExecutor().getQueue().isEmpty());
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    /**
     * DSR=0.55 > 0.40 → HardConstraintEvaluator DSR_EXCEEDED → Track 2 (하드 패일).
     * 추론 결과와 무관하게 Track 2 강제.
     */
    private static AutoReviewRequest track2Request(Long revId) {
        return new AutoReviewRequest(
                revId,
                null, 35, null, null, null, null, null, null, null, null, null, "regular",
                null, null, null, null, null, null,
                0.55, 0.50, null, null, 0, 750,   // dsr=0.55 초과 → DSR_EXCEEDED
                "MORT_001", 200_000_000L, 360, "아파트 구입 자금 대출", null,
                null, null, null, null, null, null, null
        );
    }

    /**
     * Track 1 진입 조건을 충족하는 정상 신청 요청.
     * hard constraint 통과: dsr=0.30, ltv=0.50, credit=750, delinq=0, age=35.
     */
    private static AutoReviewRequest track1Request(Long revId) {
        return new AutoReviewRequest(
                revId,
                // Layer 1
                null, 35, null, null, null, null, null, null, null, null, null, "regular",
                // Layer 2
                null, null, null, null, null, null,
                0.30, 0.50, null, null, 0, 750,
                // Layer 3
                "MORT_001", 200_000_000L, 360, "아파트 구입 자금 대출", null,
                // Layer 4
                null, null, null, null, null, null, null
        );
    }

    // ── TC 1: 동기 즉시 응답 ───────────────────────────────────────────────

    @Test
    void 평가_API_동기_응답은_즉시_PENDING_상태로_반환된다() {
        var response = restTemplate.postForEntity(
                "/api/ai/auto-review/evaluate",
                track1Request(99L),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("PENDING")     // reportStatus
                .contains("TRACK_1");    // track — 고신뢰 APPROVE + Track 1 조건 충족
    }

    // ── TC 2: 비동기 파이프라인 완료 → DONE 콜백 ─────────────────────────

    @Test
    void 비동기_LLM_파이프라인_완료_후_loan_service_에_DONE_콜백_전송() {
        restTemplate.postForEntity("/api/ai/auto-review/evaluate",
                track1Request(1L), String.class);

        // llmExecutor 스레드풀 완료 대기 + Mockito 호출 검증
        var captor = ArgumentCaptor.forClass(ReviewReportUpdateRequest.class);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(loanServiceClient).updateReport(eq(1L), captor.capture()));

        ReviewReportUpdateRequest callbackReq = captor.getValue();
        assertThat(callbackReq.status()).isEqualTo("DONE");
        assertThat(callbackReq.report()).isNotNull();
        assertThat(callbackReq.report().track().name()).isEqualTo("TRACK_1"); // StubLlmClient 산출
        assertThat(callbackReq.agentOpinionJson()).isNotNull()
                .contains("schema_version"); // AgentOpinion 직렬화 검증
    }

    // ── TC 3: revId null → 비동기 파이프라인 스킵 ────────────────────────

    @Test
    void revId_null_이면_비동기_파이프라인_스킵_후_콜백_없음() throws InterruptedException {
        restTemplate.postForEntity("/api/ai/auto-review/evaluate",
                track1Request(null), String.class);

        // 비동기가 실행됐다면 완료될 충분한 시간 대기
        TimeUnit.MILLISECONDS.sleep(600);

        verify(loanServiceClient, never()).updateReport(any(), any());
    }

    // ── TC 4: Track 3 에이전트 전체 파이프라인 — 시뮬레이션 + LLM 요약 ─

    /**
     * Track 3 E2E: 회색지대 추론 응답 → TrackClassifier → TRACK_3 →
     * PreReviewAgentService 전체 흐름 (PolicyFlag + PurposeTool + 시뮬×2 + LLM 요약) →
     * GroundingValidator 통과 → DONE 콜백 (agentOpinionJson MEDIUM, simulation 포함).
     *
     * <p>autoReviewService.review() 호출 순서:
     * 1. RuleEngineService.evaluate() → TRACK3_INFERENCE (track 분기)
     * 2. RecomputeWithTermsTool sim1 → TRACK3_SIM_IMPROVED (risk_reduced)
     * 3. RecomputeWithTermsTool sim2 → TRACK3_SIM_IMPROVED (risk_reduced)
     */
    @Test
    void Track3_에이전트_전체_파이프라인_실행_후_DONE_MEDIUM_콜백() {
        when(autoReviewService.review(any()))
                .thenReturn(TRACK3_INFERENCE)      // 트랙 분기
                .thenReturn(TRACK3_SIM_IMPROVED)   // 시뮬레이션 1
                .thenReturn(TRACK3_SIM_IMPROVED);  // 시뮬레이션 2

        restTemplate.postForEntity("/api/ai/auto-review/evaluate",
                track1Request(10L), String.class);

        var captor = ArgumentCaptor.forClass(ReviewReportUpdateRequest.class);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(loanServiceClient).updateReport(eq(10L), captor.capture()));

        ReviewReportUpdateRequest req = captor.getValue();
        assertThat(req.status()).isEqualTo("DONE");
        assertThat(req.report().track().name()).isEqualTo("TRACK_3");
        assertThat(req.agentOpinionJson())
                .isNotNull()
                .contains("schema_version")
                .contains("MEDIUM");              // RiskLevelDeriver: Track 3 + decisionScore 0.65 ≥ 0.4 → MEDIUM
        // 시뮬레이션 2건 실행 확인 (초기 + 시뮬×2 = 총 3회)
        verify(autoReviewService, times(3)).review(any());
    }

    // ── TC 5: Track 2 — 준법 검토용 마킹 + 거절 통보문 초안 ──────────────

    /**
     * Track 2 E2E: DSR 초과 hard fail → TrackClassifier TRACK_2 →
     * PreReviewAgentService.buildTrack2Opinion() →
     * RejectionReasonAgentService(StubLlmClient) →
     * DONE 콜백 (agentOpinionJson HIGH + COMPLIANCE_REVIEW_REQUIRED).
     */
    @Test
    void Track2_준법검토_마킹_COMPLIANCE_REVIEW_REQUIRED_포함_DONE_콜백() {
        restTemplate.postForEntity("/api/ai/auto-review/evaluate",
                track2Request(20L), String.class);

        var captor = ArgumentCaptor.forClass(ReviewReportUpdateRequest.class);
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(loanServiceClient).updateReport(eq(20L), captor.capture()));

        ReviewReportUpdateRequest req = captor.getValue();
        assertThat(req.status()).isEqualTo("DONE");
        assertThat(req.report().track().name()).isEqualTo("TRACK_2");
        assertThat(req.agentOpinionJson())
                .isNotNull()
                .contains("schema_version")
                .contains("HIGH")                        // Track 2 → HIGH risk level
                .contains("COMPLIANCE_REVIEW_REQUIRED"); // 준법 검토용 마킹
        verify(autoReviewService, times(1)).review(any()); // 시뮬레이션 없음
    }
}
