package com.bank.ai.agent;

import com.bank.ai.agent.guard.SemanticDisagreementDetector;
import com.bank.ai.agent.rejection.RejectionDraft;
import com.bank.ai.agent.rejection.RejectionReasonAgentService;
import com.bank.ai.llm.client.LlmCallException;
import com.bank.ai.llm.client.LlmClient;
import com.bank.ai.llm.client.LlmRequest;
import com.bank.ai.llm.config.AgentProperties;
import com.bank.ai.llm.purpose.PurposeAnalysis;
import com.bank.ai.llm.purpose.PurposeAnalysisInput;
import com.bank.ai.llm.purpose.PurposeAnalysisService;
import com.bank.ai.llm.report.GroundingValidator;
import com.bank.ai.llm.support.LlmRequestRateMeter;
import com.bank.ai.metrics.AgentMetricsRecorder;
import com.bank.ai.rag.retrieval.RagRetrievalService;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.AutoReviewResponse;
import com.bank.ai.review.service.AutoReviewService;
import com.bank.ai.rule.domain.HardFailReason;
import com.bank.ai.rule.domain.Track;
import com.bank.ai.rule.domain.TrackDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A4 PreReviewAgentService 단위 테스트.
 *
 * <p>모든 외부 의존성(LLM, inference, purpose)을 mock 으로 대체해
 * 오케스트레이션 로직과 fallback 분기를 결정론적으로 검증.
 */
@ExtendWith(MockitoExtension.class)
class PreReviewAgentServiceTest {

    @Mock LlmRequestRateMeter rateMeter;
    @Mock LlmClient llmClient;
    @Mock AutoReviewService reviewService;
    @Mock PurposeAnalysisService purposeAnalysisService;
    @Mock GroundingValidator groundingValidator;
    @Mock SemanticDisagreementDetector disagreementDetector;
    @Mock RejectionReasonAgentService rejectionReasonAgentService;
    @Mock RagRetrievalService ragRetrievalService;
    @Mock AgentMetricsRecorder metricsRecorder;

    private AgentProperties enabledProps;
    private PreReviewAgentService service;

    @BeforeEach
    void setUp() {
        enabledProps = new AgentProperties(true, 6, 2, true, 0);
        service = new PreReviewAgentService(enabledProps, rateMeter, llmClient,
                reviewService, purposeAnalysisService, groundingValidator, disagreementDetector,
                rejectionReasonAgentService, ragRetrievalService, metricsRecorder);
        // RAG 기본 stub — disabled 환경, 빈 리스트 반환 (lenient: Track 1/2 미호출)
        lenient().when(ragRetrievalService.retrieve(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        // Track 3 정상 경로 기본 stub (Track1/2 에서는 미호출 — lenient)
        lenient().when(groundingValidator.validateNumericClaims(any(), any()))
                .thenReturn(GroundingValidator.ValidationResult.ok());
        lenient().when(disagreementDetector.detect(any(), any())).thenReturn(false);
        // Track 2 기본 stub (Track1/3 에서는 미호출 — lenient)
        lenient().when(rejectionReasonAgentService.draft(any(), any(), any()))
                .thenReturn(new RejectionDraft("반려 처리되었습니다.", List.of("DSR_EXCEEDED"), null));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Track 1
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void Track1_시뮬생략_LOW_의견반환() {
        var decision = track1Decision();
        var result = service.run(1L, fullRequest(), decision);

        assertThat(result.fallbackReason()).isNull();
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.simulationResults()).isEmpty();
        verify(reviewService, never()).review(any());
        verify(llmClient, never()).call(any(), any());
    }

    @Test
    void Track1_reasoning_summary_템플릿_포함() {
        var result = service.run(1L, fullRequest(), track1Decision());
        assertThat(result.reasoningSummary()).contains("안전여유");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Track 2
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void Track2_HIGH_의견반환_COMPLIANCE_REVIEW_REQUIRED_마킹() {
        var result = service.run(1L, fullRequest(), track2Decision());

        assertThat(result.fallbackReason()).isNull();
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.simulationResults()).isEmpty();
        assertThat(result.policyFlags()).contains("COMPLIANCE_REVIEW_REQUIRED");
        assertThat(result.reasoningSummary()).isEqualTo("반려 처리되었습니다.");
        verify(rejectionReasonAgentService).draft(eq(1L), any(), any());
        verify(llmClient, never()).call(any(), any());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Track 3 — 정상 경로
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void Track3_전체_분석_결과_반환() {
        stubRateMeter(true);
        stubReviewService(0.82, 0.10);
        stubPurposeService(0.8, 0.7);
        stubLlmSummary("PD 0.3500 기반 위험도 분석 결과입니다.");

        var result = service.run(1L, fullRequest(), track3Decision());

        assertThat(result.fallbackReason()).isNull();
        assertThat(result.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.decisionScore()).isEqualTo(0.65);
        assertThat(result.pdScore()).isEqualTo(0.35);
        assertThat(result.simulationResults()).hasSize(2);
        assertThat(result.reasoningSummary()).isEqualTo("PD 0.3500 기반 위험도 분석 결과입니다.");
    }

    @Test
    void Track3_시뮬결과에_scenario_식별자_존재() {
        stubRateMeter(true);
        stubReviewService(0.82, 0.10);
        stubPurposeService(0.8, 0.7);
        stubLlmSummary("요약");

        var result = service.run(1L, fullRequest(), track3Decision());

        var scenarios = result.simulationResults().stream()
                .map(SimulationResult::scenario).toList();
        assertThat(scenarios).containsExactly(
                "loan_amount_reduction_20pct",
                "loan_period_extension_12mo"
        );
    }

    @Test
    void Track3_risk_reduced_시나리오_감지() {
        stubRateMeter(true);
        // 시뮬레이션 점수가 원래(0.65)보다 높으면 risk_reduced
        stubReviewService(0.85, 0.08);
        stubPurposeService(0.8, 0.7);
        stubLlmSummary("요약");

        var result = service.run(1L, fullRequest(), track3Decision());

        assertThat(result.simulationResults())
                .allMatch(s -> "risk_reduced".equals(s.result()));
    }

    @Test
    void Track3_LLM_호출에_올바른_promptId_전달() {
        stubRateMeter(true);
        stubReviewService(0.82, 0.10);
        stubPurposeService(0.8, 0.7);
        stubLlmSummary("요약");

        service.run(1L, fullRequest(), track3Decision());

        verify(llmClient).call(
                argThatPromptId(PreReviewAgentService.PROMPT_ID),
                eq(AgentReasoningSummary.class)
        );
    }

    @Test
    void Track3_정책플래그_DSR경고_포함() {
        stubRateMeter(true);
        stubReviewService(0.82, 0.10);
        stubPurposeService(0.8, 0.7);
        stubLlmSummary("요약");

        // dsr=0.35 >= 0.32 → DSR_THRESHOLD_WARNING
        var result = service.run(1L, fullRequest(), track3Decision());

        assertThat(result.policyFlags()).contains("DSR_THRESHOLD_WARNING");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Track 3 — fallback 분기
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void Track3_RateLimited_시_LLM미호출_template_summary() {
        stubRateMeter(false);
        stubReviewService(0.82, 0.10);
        stubPurposeService(0.8, 0.7);

        var result = service.run(1L, fullRequest(), track3Decision());

        assertThat(result.fallbackReason()).isNull(); // rate-limit은 summary fallback, 분석은 계속
        verify(llmClient, never()).call(any(), any());
        assertThat(result.reasoningSummary()).contains("심사원 검토");
    }

    @Test
    void Track3_LLM_예외시_template_summary_반환() {
        stubRateMeter(true);
        stubReviewService(0.82, 0.10);
        stubPurposeService(0.8, 0.7);
        when(llmClient.call(any(), any())).thenThrow(new LlmCallException("timeout"));

        var result = service.run(1L, fullRequest(), track3Decision());

        assertThat(result.fallbackReason()).isNull();
        assertThat(result.reasoningSummary()).contains("심사원 검토");
    }

    @Test
    void Track3_도구예외시_TOOL_ERROR_fallback() {
        stubPurposeService(0.8, 0.7);
        when(reviewService.review(any())).thenThrow(new RuntimeException("inference 연결 실패"));

        var result = service.run(1L, fullRequest(), track3Decision());

        assertThat(result.fallbackReason()).isEqualTo(FallbackReason.TOOL_ERROR);
    }

    @Test
    void Track3_LoopGuard_도구한도1일때_LOOP_GUARD_HIT() {
        var tightProps = new AgentProperties(true, 1, 2, true, 0);
        var tightService = new PreReviewAgentService(
                tightProps, rateMeter, llmClient, reviewService, purposeAnalysisService,
                groundingValidator, disagreementDetector, rejectionReasonAgentService,
                ragRetrievalService, metricsRecorder);

        var result = tightService.run(1L, fullRequest(), track3Decision());

        assertThat(result.fallbackReason()).isEqualTo(FallbackReason.LOOP_GUARD_HIT);
        verify(reviewService, never()).review(any());
    }

    @Test
    void Track3_폴백경로에서도_분포메트릭_기록됨() {
        // guard한도 1 → 첫 acquireTool 후 두 번째에서 바로 LOOP_GUARD_HIT(폴백)
        var tightProps = new AgentProperties(true, 1, 2, true, 0);
        var tightService = new PreReviewAgentService(
                tightProps, rateMeter, llmClient, reviewService, purposeAnalysisService,
                groundingValidator, disagreementDetector, rejectionReasonAgentService,
                ragRetrievalService, metricsRecorder);

        tightService.run(1L, fullRequest(), track3Decision());

        // 폴백 경로여도 finally에서 recordPerRunGuardCounts가 호출돼야 함
        verify(metricsRecorder).recordPerRunGuardCounts(
                eq(Track.TRACK_3), eq(com.bank.ai.metrics.AgentOutcome.FALLBACK),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    // ─────────────────────────────────────────────────────────────────────
    // A5 — RiskLevelDeriver / SemanticDisagreementDetector / validateNumericClaims
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void Track3_decisionScore낮으면_HIGH_riskLevel() {
        stubRateMeter(true);
        stubReviewService(0.82, 0.10);
        stubPurposeService(0.8, 0.7);
        stubLlmSummary("요약");

        // decisionScore=0.30 < 0.40 → HIGH 보정
        var decision = new TrackDecision(Track.TRACK_3, List.of(), 0.55, 0.30, 0.347, 0.104, "회색지대");
        var result = service.run(1L, fullRequest(), decision);

        assertThat(result.riskLevel()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void Track3_disagreement_감지시_opinion에_반영() {
        stubRateMeter(true);
        stubReviewService(0.82, 0.10);
        stubPurposeService(0.8, 0.7);
        stubLlmSummary("요약");
        when(disagreementDetector.detect(any(), any())).thenReturn(true);

        var result = service.run(1L, fullRequest(), track3Decision());

        assertThat(result.disagreement()).isTrue();
    }

    @Test
    void Track3_grounding_실패시_GROUNDING_FAILED_fallback() {
        stubRateMeter(true);
        stubReviewService(0.82, 0.10);
        stubPurposeService(0.8, 0.7);
        stubLlmSummary("요약");
        when(groundingValidator.validateNumericClaims(any(), any()))
                .thenReturn(GroundingValidator.ValidationResult.fail(List.of("pdScore 드리프트")));

        var result = service.run(1L, fullRequest(), track3Decision());

        assertThat(result.fallbackReason()).isEqualTo(FallbackReason.GROUNDING_FAILED);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 공통 픽스처
    // ─────────────────────────────────────────────────────────────────────

    private static AutoReviewRequest fullRequest() {
        return new AutoReviewRequest(
                1L,
                "M", 35, "SINGLE", "NONE", "1인가구", "SELF",
                "BACHELOR", "COMPUTER", "SW개발자", "강남구", "서울",
                "REGULAR",
                3, 5_000L, 20_000L, 3_000L, 0L, 3_000L,
                0.35, 0.55, 200L, 50L, 0, 640,
                "MORT_001", 10_000L, 60, "LIVING", false,
                "IT", 2, 0, 3, true, 1, 0
        );
    }

    private static TrackDecision track1Decision() {
        return new TrackDecision(Track.TRACK_1, List.of(), 0.05, 0.95, 0.347, 0.104, "PD 안전여유 이하");
    }

    private static TrackDecision track2Decision() {
        return new TrackDecision(Track.TRACK_2, List.of(HardFailReason.DSR_EXCEEDED),
                0.65, 0.30, 0.347, 0.104, "DSR 초과");
    }

    private static TrackDecision track3Decision() {
        return new TrackDecision(Track.TRACK_3, List.of(), 0.35, 0.65, 0.347, 0.104, "PD 회색지대");
    }

    private void stubRateMeter(boolean allow) {
        when(rateMeter.tryAcquire()).thenReturn(allow);
    }

    private void stubReviewService(double decisionScore, double pdScore) {
        when(reviewService.review(any())).thenReturn(
                new AutoReviewResponse("v1", "APPROVE", decisionScore,
                        Map.of("APPROVE", decisionScore, "REJECT", 1 - decisionScore),
                        pdScore, "pd_v1")
        );
    }

    private void stubPurposeService(double plausibility, double specificity) {
        when(purposeAnalysisService.analyze(any(PurposeAnalysisInput.class)))
                .thenReturn(new PurposeAnalysis(plausibility, specificity, List.of(), "ok"));
    }

    private void stubLlmSummary(String summary) {
        when(llmClient.call(any(LlmRequest.class), eq(AgentReasoningSummary.class)))
                .thenReturn(new AgentReasoningSummary(summary));
    }

    private static LlmRequest argThatPromptId(String expectedPromptId) {
        return org.mockito.ArgumentMatchers.argThat(r -> expectedPromptId.equals(r.promptId()));
    }
}
