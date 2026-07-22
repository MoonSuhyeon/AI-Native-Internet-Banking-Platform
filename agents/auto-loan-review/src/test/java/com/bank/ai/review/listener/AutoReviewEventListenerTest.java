package com.bank.ai.review.listener;

import com.bank.ai.agent.AgentOpinion;
import com.bank.ai.agent.FallbackReason;
import com.bank.ai.agent.PreReviewAgentService;
import com.bank.ai.agent.RiskLevel;
import com.bank.ai.audit.AuditLogProperties;
import com.bank.ai.audit.AuditLogService;
import com.bank.ai.llm.purpose.PurposeAnalysis;
import com.bank.ai.llm.purpose.PurposeAnalysisService;
import com.bank.ai.llm.report.ReviewReport;
import com.bank.ai.llm.report.ReviewReportService;
import com.bank.ai.metrics.AgentMetricsRecorder;
import com.bank.ai.review.client.LoanServiceClient;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.AutoReviewResponse;
import com.bank.ai.review.dto.ReviewReportUpdateRequest;
import com.bank.ai.rag.retrieval.RagRetrievalService;
import com.bank.ai.review.event.AutoReviewEvaluatedEvent;
import com.bank.ai.rule.domain.Track;
import com.bank.ai.rule.domain.TrackDecision;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AutoReviewEventListener 단위 테스트 — plan/pre-review-agent-plan.md §A6.
 *
 * <p>@Async 는 우회하여 handleAutoReviewEvaluated 를 직접 호출.
 * 5개 시나리오: 정상 DONE, revId null 스킵, 파이프라인 예외 FAILED,
 * 에이전트 예외 TOOL_ERROR fallback + DONE, 직렬화 실패 agentOpinionJson null.
 */
@ExtendWith(MockitoExtension.class)
class AutoReviewEventListenerTest {

    @Mock
    private PurposeAnalysisService purposeAnalysisService;
    @Mock
    private ReviewReportService reviewReportService;
    @Mock
    private PreReviewAgentService preReviewAgentService;
    @Mock
    private RagRetrievalService ragRetrievalService;
    @Mock
    private LoanServiceClient loanServiceClient;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private AgentMetricsRecorder metricsRecorder;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    private AutoReviewEventListener listener;

    private static final TrackDecision TRACK3_DECISION =
            new TrackDecision(Track.TRACK_3, List.of(), 0.35, 0.65, 0.347, 0.104, "회색지대");

    private static final ReviewReport STUB_REPORT =
            new ReviewReport(Track.TRACK_3, "본문", List.of(), List.of(), "권고",
                    List.of(new ReviewReport.Citation("A_V1", "src", "text")), null);

    private static final AgentOpinion STUB_OPINION =
            AgentOpinion.of(0.65, 0.35, RiskLevel.MEDIUM, List.of(), "요약", List.of(), false);

    private static final PurposeAnalysis STUB_PURPOSE =
            new PurposeAnalysis(0.9, 0.8, List.of(), "주택 구입 목적으로 타당");

    @BeforeEach
    void setUp() {
        listener = new AutoReviewEventListener(
                purposeAnalysisService, reviewReportService, preReviewAgentService,
                ragRetrievalService, loanServiceClient, auditLogService,
                new AuditLogProperties(true, false),
                metricsRecorder,
                objectMapper);
        lenient().when(ragRetrievalService.retrieve(any(), any(), any(), any(), any()))
                .thenReturn(List.of());
    }

    private AutoReviewEvaluatedEvent event(Long revId) {
        var request = new AutoReviewRequest(
                revId,
                null, 35, null, null, null, null, null, null, null, null, null, "regular",
                null, null, null, null, null, null,
                0.30, 0.50, null, null, 0, 750,
                "MORT_001", 200_000_000L, 360, "아파트 구입", null,
                null, null, null, null, null, null, null
        );
        var inference = new AutoReviewResponse("hmda_v1", "APPROVE", 0.97,
                Map.of("APPROVE", 0.97, "REJECT", 0.03), null, null);
        return new AutoReviewEvaluatedEvent(revId, request, inference, TRACK3_DECISION);
    }

    // ── TC 1: 정상 흐름 ───────────────────────────────────────────────────

    @Test
    void 정상_흐름_DONE_콜백과_agentOpinionJson_전송() {
        when(purposeAnalysisService.analyze(any())).thenReturn(STUB_PURPOSE);
        when(reviewReportService.generate(any())).thenReturn(STUB_REPORT);
        when(preReviewAgentService.run(any(), any(), any())).thenReturn(STUB_OPINION);

        listener.handleAutoReviewEvaluated(event(1L));

        var captor = ArgumentCaptor.forClass(ReviewReportUpdateRequest.class);
        verify(loanServiceClient).updateReport(eq(1L), captor.capture());
        ReviewReportUpdateRequest req = captor.getValue();
        assertThat(req.status()).isEqualTo("DONE");
        assertThat(req.report().track()).isEqualTo(Track.TRACK_3);
        assertThat(req.agentOpinionJson())
                .isNotNull()
                .contains("schema_version");
    }

    // ── TC 2: revId null → 스킵 ──────────────────────────────────────────

    @Test
    void revId_null이면_파이프라인_스킵_콜백_없음() {
        listener.handleAutoReviewEvaluated(event(null));

        verifyNoInteractions(purposeAnalysisService, reviewReportService,
                preReviewAgentService, loanServiceClient);
    }

    // ── TC 3: 파이프라인 예외 → FAILED 콜백 ─────────────────────────────

    @Test
    void 파이프라인_예외시_FAILED_콜백_전송() {
        when(purposeAnalysisService.analyze(any()))
                .thenThrow(new RuntimeException("LLM unavailable"));

        listener.handleAutoReviewEvaluated(event(2L));

        var captor = ArgumentCaptor.forClass(ReviewReportUpdateRequest.class);
        verify(loanServiceClient).updateReport(eq(2L), captor.capture());
        ReviewReportUpdateRequest req = captor.getValue();
        assertThat(req.status()).isEqualTo("FAILED");
        assertThat(req.report()).isNull();
        assertThat(req.agentOpinionJson()).isNull();
    }

    // ── TC 4: 에이전트 예외 → TOOL_ERROR fallback, DONE 전송 ─────────────

    @Test
    void 에이전트_예외시_TOOL_ERROR_fallback_포함_DONE_콜백() {
        when(purposeAnalysisService.analyze(any())).thenReturn(STUB_PURPOSE);
        when(reviewReportService.generate(any())).thenReturn(STUB_REPORT);
        when(preReviewAgentService.run(any(), any(), any()))
                .thenThrow(new RuntimeException("agent internal error"));

        listener.handleAutoReviewEvaluated(event(3L));

        var captor = ArgumentCaptor.forClass(ReviewReportUpdateRequest.class);
        verify(loanServiceClient).updateReport(eq(3L), captor.capture());
        ReviewReportUpdateRequest req = captor.getValue();
        assertThat(req.status()).isEqualTo("DONE");
        assertThat(req.agentOpinionJson())
                .isNotNull()
                .contains("TOOL_ERROR");
    }

    // ── TC 5: 직렬화 실패 → agentOpinionJson null, DONE 전송 ─────────────

    @Test
    void 직렬화_실패시_agentOpinionJson_null_DONE_콜백() throws Exception {
        when(purposeAnalysisService.analyze(any())).thenReturn(STUB_PURPOSE);
        when(reviewReportService.generate(any())).thenReturn(STUB_REPORT);
        when(preReviewAgentService.run(any(), any(), any())).thenReturn(STUB_OPINION);
        doThrow(new JsonProcessingException("serialize error") {})
                .when(objectMapper).writeValueAsString(any());

        listener.handleAutoReviewEvaluated(event(4L));

        var captor = ArgumentCaptor.forClass(ReviewReportUpdateRequest.class);
        verify(loanServiceClient).updateReport(eq(4L), captor.capture());
        ReviewReportUpdateRequest req = captor.getValue();
        assertThat(req.status()).isEqualTo("DONE");
        assertThat(req.agentOpinionJson()).isNull();
    }
}
