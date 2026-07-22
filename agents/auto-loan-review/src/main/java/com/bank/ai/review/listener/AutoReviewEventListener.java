package com.bank.ai.review.listener;

import com.bank.ai.agent.AgentOpinion;
import com.bank.ai.agent.FallbackReason;
import com.bank.ai.agent.PreReviewAgentService;
import com.bank.ai.audit.AgentAuditRecord;
import com.bank.ai.audit.AuditLogProperties;
import com.bank.ai.audit.AuditLogService;
import com.bank.ai.llm.purpose.PurposeAnalysis;
import com.bank.ai.llm.purpose.PurposeAnalysisInput;
import com.bank.ai.llm.purpose.PurposeAnalysisService;
import com.bank.ai.llm.report.ReviewReport;
import com.bank.ai.llm.report.ReviewReportInput;
import com.bank.ai.llm.report.ReviewReportService;
import com.bank.ai.rag.retrieval.RagRetrievalService;
import com.bank.ai.rag.search.Chunk;
import com.bank.ai.metrics.AgentMetricsRecorder;
import com.bank.ai.metrics.AgentOutcome;
import com.bank.ai.shadow.ShadowModeService;
import com.bank.ai.review.client.LoanServiceClient;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.ReviewReportUpdateRequest;
import com.bank.ai.review.event.AutoReviewEvaluatedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 비동기 LLM 파이프라인 리스너 — plan/llm-pipeline.md §7, pre-review-agent-plan.md §아키텍처.
 *
 * <p>흐름:
 * <ol>
 *   <li>PurposeAnalysis — 신청 사유 분석</li>
 *   <li>ReviewReport — 트랙별 심사 리포트 생성</li>
 *   <li>PreReviewAgentService — 에이전트 의견 생성 (30s 타임아웃, A6 신규)</li>
 *   <li>loan-service PATCH — 결과 업데이트 (agent_opinion_json 포함)</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoReviewEventListener {

    static final int AGENT_TIMEOUT_SECONDS = 30;

    private final PurposeAnalysisService purposeAnalysisService;
    private final ReviewReportService reviewReportService;
    private final PreReviewAgentService preReviewAgentService;
    private final RagRetrievalService ragRetrievalService;
    private final LoanServiceClient loanServiceClient;
    private final AuditLogService auditLogService;
    private final AuditLogProperties auditLogProperties;
    private final AgentMetricsRecorder metricsRecorder;
    private final ObjectMapper objectMapper;

    /** Shadow Mode 비활성 시 null — @ConditionalOnProperty 로 빈 미생성. */
    @Nullable
    @Autowired(required = false)
    private ShadowModeService shadowModeService;

    @Async("llmExecutor")
    @EventListener
    public void handleAutoReviewEvaluated(AutoReviewEvaluatedEvent event) {
        if (event.revId() == null) {
            log.warn("Async LLM pipeline skipped: revId is null (likely standalone /evaluate call)");
            return;
        }

        log.info("Starting async LLM pipeline for revId: {}", event.revId());

        try {
            // Step 1: Purpose Analysis
            PurposeAnalysis purpose = analyzePurpose(event.request());
            log.info("Purpose analysis done: {}", purpose);

            // Step 2: Review Report
            ReviewReport report = generateReport(event, purpose);
            log.info("Review report generated: track={}", report.track());

            // Step 3: PreReviewAgentService (30s 타임아웃)
            Instant agentStart = Instant.now();
            AgentOpinion opinion = runAgentWithTimeout(event);
            Duration agentDuration = Duration.between(agentStart, Instant.now());
            log.info("Agent opinion: riskLevel={} fallback={}", opinion.riskLevel(), opinion.fallbackReason());

            // Step 3-M: 에이전트 메트릭 기록
            AgentOutcome agentOutcome = opinion.fallbackReason() != null
                    ? AgentOutcome.FALLBACK : AgentOutcome.SUCCESS;
            metricsRecorder.recordRun(event.decision().track(), agentOutcome, agentDuration);
            if (opinion.fallbackReason() != null) {
                metricsRecorder.recordFallback(opinion.fallbackReason());
            }
            if (opinion.disagreement()) {
                metricsRecorder.recordDisagreement(event.decision().track());
            }

            String agentOpinionJson = serializeOpinion(opinion, event.revId());

            // Step 4: 감사 로그 기록 (REQUIRES_NEW — 메인 트랜잭션과 독립 커밋)
            recordAudit(event, opinion, agentOpinionJson);

            // Step 5: loan-service 에 결과 전송
            loanServiceClient.updateReport(event.revId(),
                    new ReviewReportUpdateRequest("DONE", report, agentOpinionJson));
            log.info("Async LLM pipeline completed for revId: {}", event.revId());

            // Step 6: Shadow run (ai.shadow.enabled=true 시에만 빈 존재)
            if (shadowModeService != null) {
                shadowModeService.runShadow(event.revId(), event.request(), event.decision(), opinion);
            }

        } catch (Exception e) {
            log.error("Async LLM pipeline failed for revId: {}", event.revId(), e);
            metricsRecorder.recordRun(event.decision().track(), AgentOutcome.ERROR, Duration.ZERO);
            try {
                loanServiceClient.updateReport(event.revId(),
                        new ReviewReportUpdateRequest("FAILED", null, null));
            } catch (Exception callbackEx) {
                log.error("Failed to send FAILED status callback for revId: {}", event.revId(), callbackEx);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────

    private void recordAudit(AutoReviewEvaluatedEvent event, AgentOpinion opinion,
                             String agentOpinionJson) {
        try {
            var auditRecord = AgentAuditRecord.from(
                    event, opinion, objectMapper, auditLogProperties.includeRawLlmResponse());
            auditLogService.record(auditRecord);

            // 감사 로그 크기 메트릭 — opinionJson UTF-8 byte 기준
            if (agentOpinionJson != null) {
                metricsRecorder.recordAuditLogSize(
                        agentOpinionJson.getBytes(StandardCharsets.UTF_8).length);
            }
        } catch (Exception e) {
            // 감사 로그 저장 실패는 파이프라인을 중단하지 않음 — ERROR 로그만 기록
            log.error("[Audit] 감사 로그 저장 실패 revId={} — 파이프라인 계속 진행", event.revId(), e);
        }
    }

    private AgentOpinion runAgentWithTimeout(AutoReviewEvaluatedEvent event) {
        var future = CompletableFuture.supplyAsync(
                () -> preReviewAgentService.run(event.revId(), event.request(), event.decision()))
                .orTimeout(AGENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                log.warn("PreReviewAgentService timeout {}s revId={}", AGENT_TIMEOUT_SECONDS, event.revId());
                return AgentOpinion.fallback(FallbackReason.AGENT_TIMEOUT);
            }
            log.error("PreReviewAgentService error revId={}", event.revId(), e.getCause());
            return AgentOpinion.fallback(FallbackReason.TOOL_ERROR);
        }
    }

    private String serializeOpinion(AgentOpinion opinion, Long revId) {
        try {
            return objectMapper.writeValueAsString(opinion);
        } catch (JsonProcessingException e) {
            log.error("AgentOpinion 직렬화 실패 revId={}", revId, e);
            return null;
        }
    }

    private PurposeAnalysis analyzePurpose(AutoReviewRequest req) {
        var input = new PurposeAnalysisInput(
                "persona_summary_stub",
                req.purposeCd(),
                req.productCode(),
                req.requestedAmountKw() != null ? req.requestedAmountKw() / 10000 : 0,
                req.requestedPeriodMo()
        );
        return purposeAnalysisService.analyze(input);
    }

    private ReviewReport generateReport(AutoReviewEvaluatedEvent event, PurposeAnalysis purpose) {
        String policyQuery = buildPolicyQuery(event);
        String casesQuery  = buildCasesQuery(event);
        List<Chunk> ragChunks = ragRetrievalService.retrieve(
                event.decision().track(), policyQuery,
                casesQuery, event.request().productCode(), null);

        var input = new ReviewReportInput(
                event.decision().track(),
                event.inference().pdScore() != null ? event.inference().pdScore() : 0.0,
                event.inference().decisionScore(),
                event.decision().pdThreshold(),
                event.decision().safetyMarginThreshold(),
                event.decision().hardFails(),
                "persona_summary_stub",
                event.request().productCode(),
                purpose,
                ragChunks
        );
        return reviewReportService.generate(input);
    }

    private static String buildPolicyQuery(AutoReviewEvaluatedEvent event) {
        String product = event.request().productCode() != null ? event.request().productCode() : "";
        String purpose = event.request().purposeCd() != null ? event.request().purposeCd() : "";
        return (product + " " + purpose + " 정책 한도 기준").trim();
    }

    private static String buildCasesQuery(AutoReviewEvaluatedEvent event) {
        var req = event.request();
        List<String> parts = new java.util.ArrayList<>();
        if (req.productCode() != null)           parts.add(req.productCode());
        if (req.applicantSegment() != null)      parts.add(req.applicantSegment());
        if (req.dsr() != null)                   parts.add(String.format("DSR %.0f%%", req.dsr() * 100));
        if (req.ltv() != null)                   parts.add(String.format("LTV %.0f%%", req.ltv() * 100));
        if (req.creditScoreProxy() != null)      parts.add("신용점수 " + req.creditScoreProxy());
        if (req.delinquencyHistory24m() != null) parts.add("연체 " + req.delinquencyHistory24m() + "건");
        return parts.isEmpty() ? null : String.join(" ", parts) + " 유사 케이스";
    }
}
