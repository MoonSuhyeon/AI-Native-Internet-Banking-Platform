package com.bank.loan.advisory.agent;

import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.domain.ReviewAdvisorySignal;
import com.bank.loan.advisory.domain.audit.AiAuditOpinion;
import com.bank.loan.advisory.domain.audit.ReviewerRiskScore;
import com.bank.loan.advisory.event.QuarantineTriggeredEvent;
import com.bank.loan.advisory.gateway.AiGatewayClient;
import com.bank.loan.advisory.gateway.AiGatewayProperties;
import com.bank.loan.advisory.gateway.GatewayAnalysisRequest;
import com.bank.loan.advisory.gateway.GatewayAnalysisRequest.GatewayRagChunk;
import com.bank.loan.advisory.gateway.GatewayAnalysisResponse;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import com.bank.loan.advisory.repository.ReviewAdvisorySignalRepository;
import com.bank.loan.advisory.repository.audit.AiAuditOpinionRepository;
import com.bank.loan.advisory.repository.audit.ReviewerRiskScoreRepository;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.repository.LoanReviewRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class AuditFairnessAgentTest {

    ReviewAdvisoryReportRepository  reportRepo     = mock(ReviewAdvisoryReportRepository.class);
    ReviewAdvisorySignalRepository  signalRepo     = mock(ReviewAdvisorySignalRepository.class);
    LoanReviewRepository            reviewRepo     = mock(LoanReviewRepository.class);
    AiGatewayClient                 gateway        = mock(AiGatewayClient.class);
    AiAuditOpinionRepository        opinionRepo    = mock(AiAuditOpinionRepository.class);
    ReviewerRiskScoreRepository     riskRepo       = mock(ReviewerRiskScoreRepository.class);
    ApplicationEventPublisher       eventPublisher = mock(ApplicationEventPublisher.class);
    ObjectMapper                    objectMapper   = new ObjectMapper();
    AiGatewayProperties             properties     = new AiGatewayProperties();

    AuditFairnessAgent agent;

    @BeforeEach
    void setUp() {
        agent = new AuditFairnessAgent(
                reportRepo, signalRepo, reviewRepo, gateway,
                opinionRepo, riskRepo, eventPublisher, objectMapper, properties);
        when(riskRepo.findByReviewerId(anyLong())).thenReturn(Optional.empty());
        when(riskRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(opinionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reportRepo.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void BIAS_신호_있으면_BIAS_DETECTION_타입으로_게이트웨이_호출() {
        Long revId = 1001L;
        Long reviewerId = 501L;
        setupReportAndSignal(revId, reviewerId, AuditFairnessAgent.ADVISORY_TYPE_BIAS, "WARN", null);
        setupLoanReview(revId, reviewerId, "주관적 판단에 의존한 거절");
        when(gateway.analyze(any())).thenReturn(biasResponse("BIAS_SUSPECTED", 0.85));

        agent.analyzeReports(List.of(1L));

        ArgumentCaptor<GatewayAnalysisRequest> captor = ArgumentCaptor.forClass(GatewayAnalysisRequest.class);
        verify(gateway).analyze(captor.capture());
        assertThat(captor.getValue().analysisType()).isEqualTo("BIAS_DETECTION");
        assertThat(captor.getValue().revId()).isEqualTo(revId);
    }

    @Test
    void COMPLIANCE_신호_있으면_COMPLIANCE_VERIFICATION_타입으로_게이트웨이_호출() {
        Long revId = 1002L;
        Long reviewerId = 502L;
        setupReportAndSignal(revId, reviewerId, "REREVIEW_RECOMMEND", "CRITICAL", null);
        setupLoanReview(revId, reviewerId, null);
        when(gateway.analyze(any())).thenReturn(complianceResponse("VIOLATION_SUSPECTED", 0.9));

        agent.analyzeReports(List.of(2L));

        ArgumentCaptor<GatewayAnalysisRequest> captor = ArgumentCaptor.forClass(GatewayAnalysisRequest.class);
        verify(gateway).analyze(captor.capture());
        assertThat(captor.getValue().analysisType()).isEqualTo("COMPLIANCE_VERIFICATION");
    }

    @Test
    void 게이트웨이_실패해도_예외_전파_안함() {
        Long revId = 1003L;
        Long reviewerId = 503L;
        setupReportAndSignal(revId, reviewerId, AuditFairnessAgent.ADVISORY_TYPE_BIAS, "WARN", null);
        setupLoanReview(revId, reviewerId, null);
        when(gateway.analyze(any())).thenThrow(new RuntimeException("timeout"));

        assertThatNoException().isThrownBy(() -> agent.analyzeReports(List.of(3L)));
        verify(opinionRepo, never()).save(any());
    }

    @Test
    void BIAS_SUSPECTED_결론이면_biasScore_증가() {
        Long revId = 1004L;
        Long reviewerId = 504L;
        setupReportAndSignal(revId, reviewerId, AuditFairnessAgent.ADVISORY_TYPE_BIAS, "WARN", null);
        setupLoanReview(revId, reviewerId, null);
        when(gateway.analyze(any())).thenReturn(biasResponse("BIAS_SUSPECTED", 0.8));

        agent.analyzeReports(List.of(4L));

        ArgumentCaptor<ReviewerRiskScore> scoreCaptor = ArgumentCaptor.forClass(ReviewerRiskScore.class);
        verify(riskRepo).save(scoreCaptor.capture());
        assertThat(scoreCaptor.getValue().getBiasScore()).isEqualTo(5.0);
    }

    @Test
    void BIAS_SUSPECTED_결론이면_리포트_격리_및_이벤트_발행() {
        Long revId = 1005L;
        Long reviewerId = 505L;
        setupReportAndSignal(revId, reviewerId, AuditFairnessAgent.ADVISORY_TYPE_BIAS, "WARN", null);
        setupLoanReview(revId, reviewerId, null);
        when(gateway.analyze(any())).thenReturn(biasResponse("BIAS_SUSPECTED", 0.88));

        agent.analyzeReports(List.of(5L));

        verify(reportRepo).saveAll(any());

        ArgumentCaptor<QuarantineTriggeredEvent> eventCaptor =
                ArgumentCaptor.forClass(QuarantineTriggeredEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        QuarantineTriggeredEvent event = eventCaptor.getValue();
        assertThat(event.revId()).isEqualTo(revId);
        assertThat(event.conclusionCd()).isEqualTo("BIAS_SUSPECTED");
        assertThat(event.analysisType()).isEqualTo("BIAS_DETECTION");
    }

    @Test
    void NO_BIAS_결론이면_격리_없음() {
        Long revId = 1006L;
        Long reviewerId = 506L;
        setupReportAndSignal(revId, reviewerId, AuditFairnessAgent.ADVISORY_TYPE_BIAS, "WARN", null);
        setupLoanReview(revId, reviewerId, null);
        when(gateway.analyze(any())).thenReturn(biasResponse("NO_BIAS_DETECTED", 0.91));

        agent.analyzeReports(List.of(6L));

        verify(reportRepo, never()).saveAll(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void VIOLATION_SUSPECTED_결론이면_격리_및_이벤트_발행() {
        Long revId = 1007L;
        Long reviewerId = 507L;
        setupReportAndSignal(revId, reviewerId, "REREVIEW_RECOMMEND", "CRITICAL", null);
        setupLoanReview(revId, reviewerId, null);
        when(gateway.analyze(any())).thenReturn(complianceResponse("VIOLATION_SUSPECTED", 0.93));

        agent.analyzeReports(List.of(7L));

        ArgumentCaptor<QuarantineTriggeredEvent> eventCaptor =
                ArgumentCaptor.forClass(QuarantineTriggeredEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().conclusionCd()).isEqualTo("VIOLATION_SUSPECTED");
        assertThat(eventCaptor.getValue().analysisType()).isEqualTo("COMPLIANCE_VERIFICATION");
    }

    @Test
    void INFO_심각도_리포트는_분석_대상_아님() {
        ReviewAdvisoryReport infoReport = mockReport(9L, 2001L, 601L, "INFO",
                AuditFairnessAgent.ADVISORY_TYPE_BIAS, null);
        when(reportRepo.findAllById(List.of(9L))).thenReturn(List.of(infoReport));

        agent.analyzeReports(List.of(9L));

        verify(gateway, never()).analyze(any());
    }

    @Test
    void 빈_목록_전달시_아무것도_호출_안함() {
        agent.analyzeReports(List.of());
        verifyNoInteractions(gateway, opinionRepo, riskRepo);
    }

    // ── RAG 청크 주입 시나리오 ──────────────────────────────────

    @Test
    void CRITICAL_리포트_citations_있으면_ragChunks_채워서_게이트웨이_호출() {
        Long revId = 2001L;
        Long reviewerId = 601L;
        String payload = """
                {"citations":[
                  {"chunkId":101,"docId":1,"docCd":"KDB-2024","docTitle":"여신심사 가이드",
                   "sectionPath":"3.2","chunkText":"심사 기준 내용","score":0.95}
                ]}""";
        setupReportAndSignal(revId, reviewerId, "REREVIEW_RECOMMEND", "CRITICAL", payload);
        setupLoanReview(revId, reviewerId, null);
        when(gateway.analyze(any())).thenReturn(complianceResponse("NO_VIOLATION", 0.7));

        agent.analyzeReports(List.of(11L));

        ArgumentCaptor<GatewayAnalysisRequest> captor = ArgumentCaptor.forClass(GatewayAnalysisRequest.class);
        verify(gateway).analyze(captor.capture());
        List<GatewayRagChunk> chunks = captor.getValue().ragChunks();
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).source()).isEqualTo("KDB-2024 §3.2");
        assertThat(chunks.get(0).content()).isEqualTo("심사 기준 내용");
    }

    @Test
    void WARN_리포트_citations_없으면_ragChunks_비어서_게이트웨이_호출() {
        Long revId = 2002L;
        Long reviewerId = 602L;
        setupReportAndSignal(revId, reviewerId, AuditFairnessAgent.ADVISORY_TYPE_BIAS, "WARN", null);
        setupLoanReview(revId, reviewerId, null);
        when(gateway.analyze(any())).thenReturn(biasResponse("NO_BIAS_DETECTED", 0.7));

        agent.analyzeReports(List.of(12L));

        ArgumentCaptor<GatewayAnalysisRequest> captor = ArgumentCaptor.forClass(GatewayAnalysisRequest.class);
        verify(gateway).analyze(captor.capture());
        assertThat(captor.getValue().ragChunks()).isEmpty();
    }

    @Test
    void 잘못된_JSON_payload_이면_예외_없이_ragChunks_빈채로_진행() {
        Long revId = 2003L;
        Long reviewerId = 603L;
        setupReportAndSignal(revId, reviewerId, "REREVIEW_RECOMMEND", "CRITICAL", "NOT_VALID_JSON{{");
        setupLoanReview(revId, reviewerId, null);
        when(gateway.analyze(any())).thenReturn(complianceResponse("NO_VIOLATION", 0.6));

        assertThatNoException().isThrownBy(() -> agent.analyzeReports(List.of(13L)));

        ArgumentCaptor<GatewayAnalysisRequest> captor = ArgumentCaptor.forClass(GatewayAnalysisRequest.class);
        verify(gateway).analyze(captor.capture());
        assertThat(captor.getValue().ragChunks()).isEmpty();
    }

    @Test
    void 동일_chunkId_중복이면_중복_제거_후_전달() {
        Long revId = 2004L;
        Long reviewerId = 604L;
        // 두 리포트가 같은 chunkId=101 을 가짐 → 점수 높은 것 하나만 남아야 함
        String payload1 = """
                {"citations":[
                  {"chunkId":101,"docId":1,"docCd":"KDB-2024","docTitle":"T","sectionPath":"1",
                   "chunkText":"내용A","score":0.80}
                ]}""";
        String payload2 = """
                {"citations":[
                  {"chunkId":101,"docId":1,"docCd":"KDB-2024","docTitle":"T","sectionPath":"1",
                   "chunkText":"내용A","score":0.90}
                ]}""";

        ReviewAdvisoryReport r1 = mockReport(14L, revId, reviewerId, "CRITICAL", "REREVIEW_RECOMMEND", payload1);
        ReviewAdvisoryReport r2 = mockReport(15L, revId, reviewerId, "CRITICAL", "REREVIEW_RECOMMEND", payload2);
        when(reportRepo.findAllById(any())).thenReturn(List.of(r1, r2));
        ReviewAdvisorySignal sig14 = mockSignal(14L);
        when(signalRepo.findByAdvrIdOrderByObservedAtAsc(14L)).thenReturn(List.of(sig14));
        when(signalRepo.findByAdvrIdOrderByObservedAtAsc(15L)).thenReturn(List.of());
        setupLoanReview(revId, reviewerId, null);
        when(gateway.analyze(any())).thenReturn(complianceResponse("NO_VIOLATION", 0.7));

        agent.analyzeReports(List.of(14L, 15L));

        ArgumentCaptor<GatewayAnalysisRequest> captor = ArgumentCaptor.forClass(GatewayAnalysisRequest.class);
        verify(gateway).analyze(captor.capture());
        assertThat(captor.getValue().ragChunks()).hasSize(1);
    }

    @Test
    void max_chunks_초과시_상한_적용() {
        Long revId = 2005L;
        Long reviewerId = 605L;
        // 기본 maxChunks=5, 7개 citations → 5개만 전달
        String payload = """
                {"citations":[
                  {"chunkId":1,"docId":1,"docCd":"D","docTitle":"T","sectionPath":"1","chunkText":"c","score":0.91},
                  {"chunkId":2,"docId":1,"docCd":"D","docTitle":"T","sectionPath":"2","chunkText":"c","score":0.90},
                  {"chunkId":3,"docId":1,"docCd":"D","docTitle":"T","sectionPath":"3","chunkText":"c","score":0.89},
                  {"chunkId":4,"docId":1,"docCd":"D","docTitle":"T","sectionPath":"4","chunkText":"c","score":0.88},
                  {"chunkId":5,"docId":1,"docCd":"D","docTitle":"T","sectionPath":"5","chunkText":"c","score":0.87},
                  {"chunkId":6,"docId":1,"docCd":"D","docTitle":"T","sectionPath":"6","chunkText":"c","score":0.86},
                  {"chunkId":7,"docId":1,"docCd":"D","docTitle":"T","sectionPath":"7","chunkText":"c","score":0.85}
                ]}""";
        setupReportAndSignal(revId, reviewerId, "REREVIEW_RECOMMEND", "CRITICAL", payload);
        setupLoanReview(revId, reviewerId, null);
        when(gateway.analyze(any())).thenReturn(complianceResponse("NO_VIOLATION", 0.7));

        agent.analyzeReports(List.of(16L));

        ArgumentCaptor<GatewayAnalysisRequest> captor = ArgumentCaptor.forClass(GatewayAnalysisRequest.class);
        verify(gateway).analyze(captor.capture());
        assertThat(captor.getValue().ragChunks()).hasSize(5);
    }

    // ── helpers ──────────────────────────────────────────────────

    private void setupReportAndSignal(Long revId, Long reviewerId, String advisoryTypeCd,
                                      String severity, String payload) {
        long advrId = Math.abs(revId % 1000) + 1;
        ReviewAdvisoryReport report = mockReport(advrId, revId, reviewerId, severity, advisoryTypeCd, payload);
        when(reportRepo.findAllById(any())).thenReturn(List.of(report));
        ReviewAdvisorySignal signal = mockSignal(advrId);
        when(signalRepo.findByAdvrIdOrderByObservedAtAsc(advrId)).thenReturn(List.of(signal));
    }

    private void setupLoanReview(Long revId, Long reviewerId, String remark) {
        LoanReview review = LoanReview.builder()
                .applId(revId + 10000)
                .revTypeCd(LoanReview.TYPE_MANUAL)
                .revStatusCd(LoanReview.STATUS_COMPLETED)
                .revDecisionCd(LoanReview.DECISION_APPROVED)
                .reviewerId(reviewerId)
                .revRemark(remark)
                .reviewedAt(OffsetDateTime.now())
                .build();
        when(reviewRepo.findById(revId)).thenReturn(Optional.of(review));
    }

    private ReviewAdvisoryReport mockReport(Long advrId, Long revId, Long reviewerId,
                                            String severity, String advisoryTypeCd, String payload) {
        ReviewAdvisoryReport r = mock(ReviewAdvisoryReport.class);
        when(r.getAdvrId()).thenReturn(advrId);
        when(r.getRevId()).thenReturn(revId);
        when(r.getTargetReviewerId()).thenReturn(reviewerId);
        when(r.getSeverityCd()).thenReturn(severity);
        when(r.getAdvisoryTypeCd()).thenReturn(advisoryTypeCd);
        when(r.getAdvrPayload()).thenReturn(payload);
        return r;
    }

    private ReviewAdvisorySignal mockSignal(Long advrId) {
        ReviewAdvisorySignal s = mock(ReviewAdvisorySignal.class);
        when(s.getAdvrId()).thenReturn(advrId);
        when(s.getSignalMetric()).thenReturn("approve_rate_bps");
        when(s.getObservedValue()).thenReturn(BigDecimal.valueOf(7500));
        when(s.getThresholdValue()).thenReturn(BigDecimal.valueOf(5000));
        return s;
    }

    private GatewayAnalysisResponse biasResponse(String conclusion, double confidence) {
        return new GatewayAnalysisResponse("BIAS_DETECTION", conclusion, "편향 의심 의견", confidence, 100, 80, List.of());
    }

    private GatewayAnalysisResponse complianceResponse(String conclusion, double confidence) {
        return new GatewayAnalysisResponse("COMPLIANCE_VERIFICATION", conclusion, "규정 위반 의심", confidence, 120, 90, List.of());
    }
}
