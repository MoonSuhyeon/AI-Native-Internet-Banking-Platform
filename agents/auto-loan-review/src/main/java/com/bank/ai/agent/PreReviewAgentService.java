package com.bank.ai.agent;

import com.bank.ai.agent.guard.AgentLoopGuard;
import com.bank.ai.agent.guard.SemanticDisagreementDetector;
import com.bank.ai.agent.rejection.RejectionReasonAgentService;
import com.bank.ai.agent.tools.PolicyFlagTool;
import com.bank.ai.agent.tools.PurposeAnalysisTool;
import com.bank.ai.agent.tools.RecomputeWithTermsTool;
import com.bank.ai.metrics.AgentMetricsRecorder;
import com.bank.ai.metrics.AgentOutcome;
import com.bank.ai.rag.retrieval.RagRetrievalService;
import com.bank.ai.rag.search.Chunk;
import com.bank.ai.llm.client.LlmCallException;
import com.bank.ai.llm.client.LlmClient;
import com.bank.ai.llm.client.LlmRequest;
import com.bank.ai.llm.config.AgentProperties;
import com.bank.ai.llm.purpose.PurposeAnalysisService;
import com.bank.ai.llm.report.GroundingValidator;
import com.bank.ai.llm.support.LlmRequestRateMeter;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.service.AutoReviewService;
import com.bank.ai.rule.domain.Track;
import com.bank.ai.rule.domain.TrackDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 사전 심사 에이전트 오케스트레이터 — pre-review-agent-plan.md §아키텍처.
 *
 * <p>Track 별 분기:
 * <ul>
 *   <li>Track 1 — 시뮬레이션 생략, 정책 플래그만 수집 후 LOW 의견 반환</li>
 *   <li>Track 2 — A8~A9 scope, 현 단계에서는 HIGH 의견만 반환</li>
 *   <li>Track 3 — PolicyFlag + PurposeAnalysis + 시뮬레이션(2 시나리오) + LLM 요약</li>
 * </ul>
 *
 * <p>진입 시 {@code loan_review.agent_opinion_json IS NOT NULL} 조회 → 이미 있으면 즉시 skip
 * (멱등성) — A6 트리거 연결 시 구현.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreReviewAgentService {

    static final String PROMPT_ID = "agent_reasoning_summary";
    static final int PROMPT_VER = 1;

    private static final String SYSTEM_PROMPT =
            "당신은 대출 심사 분석 전문가입니다. " +
            "제공된 심사 결과를 바탕으로 1~2문장의 한국어 위험도 요약을 작성하세요. " +
            "수치 근거를 포함하고, 판단 편향 없이 사실 기반으로 서술하세요.";

    private final AgentProperties agentProps;
    private final LlmRequestRateMeter rateMeter;
    private final LlmClient llmClient;
    private final AutoReviewService reviewService;
    private final PurposeAnalysisService purposeAnalysisService;
    private final GroundingValidator groundingValidator;
    private final SemanticDisagreementDetector disagreementDetector;
    private final RejectionReasonAgentService rejectionReasonAgentService;
    private final RagRetrievalService ragRetrievalService;
    private final AgentMetricsRecorder metricsRecorder;

    /**
     * @param revId    loan_review PK (멱등성 체크 및 로그용)
     * @param request  자동심사 입력 (59 필드)
     * @param decision RuleEngine 트랙 분기 결과
     * @return AgentOpinion — fallback_reason != null 이면 분석 미실행
     */
    public AgentOpinion run(Long revId, AutoReviewRequest request, TrackDecision decision) {
        if (!agentProps.enabled()) {
            log.info("PreReviewAgentService: kill switch — AGENT_DISABLED revId={}", revId);
            return AgentOpinion.fallback(FallbackReason.AGENT_DISABLED);
        }

        if (decision.track() == Track.TRACK_1) {
            log.debug("PreReviewAgentService: Track 1 시뮬 생략 revId={}", revId);
            return buildTrack1Opinion(request, decision);
        }

        if (decision.track() == Track.TRACK_2) {
            log.debug("PreReviewAgentService: Track 2 트리거 revId={}", revId);
            return buildTrack2Opinion(revId, request, decision);
        }

        return runTrack3(revId, request, decision);
    }

    // ─────────────────────────────────────────────────────────────────────

    private AgentOpinion runTrack3(Long revId, AutoReviewRequest request, TrackDecision decision) {
        var guard = new AgentLoopGuard(agentProps.maxToolCalls(), agentProps.maxLlmCalls());
        AgentOpinion result = null;
        AgentOutcome outcome = AgentOutcome.ERROR;

        try {
            // 1. 정책 소프트 경고 플래그
            if (!guard.acquireTool()) {
                result = loopGuardFallback(revId);
                outcome = AgentOutcome.FALLBACK;
                return result;
            }
            List<String> policyFlags = new PolicyFlagTool(request).evaluatePolicyFlags();

            // 2. RAG 검색 — D2 정책 코퍼스 + D3 유사 케이스 코퍼스
            String policyQuery = buildPolicyQuery(request);
            String casesQuery  = buildCasesQuery(request);
            List<Chunk> ragChunks = ragRetrievalService.retrieve(
                    decision.track(), policyQuery, casesQuery, request.productCode(), guard);
            log.debug("PreReviewAgentService: RAG chunks={} revId={}", ragChunks.size(), revId);

            // 3. 신청 사유 분석
            if (!guard.acquireTool()) {
                result = loopGuardFallback(revId);
                outcome = AgentOutcome.FALLBACK;
                return result;
            }
            PurposeAnalysisTool.PurposeAnalysisResult purpose =
                    new PurposeAnalysisTool(purposeAnalysisService, request).analyzePurpose(null);

            // 3. What-if 시뮬레이션 (2 시나리오)
            List<SimulationResult> simulations = runSimulations(guard, request, decision, revId);
            if (simulations == null) {
                result = loopGuardFallback(revId);
                outcome = AgentOutcome.FALLBACK;
                return result;
            }

            // 4. LLM 추론 요약
            String summary = generateReasoningSummary(
                    guard, request, decision, policyFlags, purpose, simulations, revId);

            RiskLevel riskLevel = RiskLevelDeriver.derive(decision);
            boolean disagreement = disagreementDetector.detect(riskLevel, summary);

            AgentOpinion opinion = AgentOpinion.of(
                    decisionScoreOrZero(decision),
                    decision.pd(),
                    riskLevel,
                    policyFlags,
                    summary,
                    simulations,
                    disagreement
            );

            // 수치 클레임 그라운딩 검증
            var groundingResult = groundingValidator.validateNumericClaims(opinion, decision);
            if (!groundingResult.passed()) {
                log.warn("PreReviewAgentService: GROUNDING_FAILED revId={} issues={}",
                        revId, groundingResult.issues());
                result = AgentOpinion.fallback(FallbackReason.GROUNDING_FAILED);
                outcome = AgentOutcome.FALLBACK;
                return result;
            }

            log.info("PreReviewAgentService: Track 3 완료 revId={} tools={} llm={} disagreement={}",
                    revId, guard.getToolCallCount(), guard.getLlmCallCount(), disagreement);

            result = opinion;
            outcome = AgentOutcome.SUCCESS;
            return result;

        } catch (Exception e) {
            log.error("PreReviewAgentService: Track 3 오류 revId={}", revId, e);
            result = AgentOpinion.fallback(FallbackReason.TOOL_ERROR);
            outcome = AgentOutcome.ERROR;
            return result;
        } finally {
            metricsRecorder.recordPerRunGuardCounts(
                    decision.track(), outcome,
                    guard.getToolCallCount(), guard.getLlmCallCount());
        }
    }

    private List<SimulationResult> runSimulations(
            AgentLoopGuard guard,
            AutoReviewRequest request,
            TrackDecision decision,
            Long revId
    ) {
        var tool = new RecomputeWithTermsTool(reviewService, request);
        var results = new ArrayList<SimulationResult>();

        // 시나리오 1: loan_amount_reduction_20pct
        if (!guard.acquireTool()) {
            log.warn("PreReviewAgentService: LOOP_GUARD_HIT (시뮬1) revId={}", revId);
            return null;
        }
        Long origAmount = request.requestedAmountKw();
        if (origAmount != null) {
            var r1 = tool.recomputeWithTerms((long) (origAmount * 0.8), null);
            results.add(toSimResult("loan_amount_reduction_20pct", r1, decision));
        }

        // 시나리오 2: loan_period_extension_12mo
        if (!guard.acquireTool()) {
            log.warn("PreReviewAgentService: LOOP_GUARD_HIT (시뮬2) revId={}", revId);
            return results; // 부분 결과 반환
        }
        Integer origPeriod = request.requestedPeriodMo();
        if (origPeriod != null) {
            var r2 = tool.recomputeWithTerms(null, origPeriod + 12);
            results.add(toSimResult("loan_period_extension_12mo", r2, decision));
        }

        return results;
    }

    private SimulationResult toSimResult(
            String scenario,
            RecomputeWithTermsTool.RecomputeResult r,
            TrackDecision decision
    ) {
        double origDecision = decisionScoreOrZero(decision);
        double newDecision = r.newDecisionScore() != null ? r.newDecisionScore() : origDecision;
        double delta = newDecision - origDecision;

        String resultCode = delta > 0.05 ? "risk_reduced"
                : delta < -0.05 ? "risk_increased"
                : "no_change";

        String suggestion = switch (scenario) {
            case "loan_amount_reduction_20pct" -> "risk_reduced".equals(resultCode)
                    ? "대출 금액 20% 감소 시 승인 가능성이 높아집니다."
                    : "대출 금액 20% 감소만으로는 위험도 개선 효과가 제한적입니다.";
            case "loan_period_extension_12mo" -> "risk_reduced".equals(resultCode)
                    ? "상환 기간 12개월 연장 시 월 상환 부담 감소로 승인 가능성이 높아집니다."
                    : "상환 기간 연장만으로는 위험도 개선이 제한적입니다.";
            default -> "조건 변경 시뮬레이션 결과를 참고하시기 바랍니다.";
        };

        return new SimulationResult(
                scenario,
                r.mutatedAmountKw(),
                r.mutatedPeriodMo(),
                newDecision,
                r.newPdScore() != null ? r.newPdScore() : decision.pd(),
                resultCode,
                suggestion,
                false // Track 3은 hard constraint 이미 통과
        );
    }

    private String generateReasoningSummary(
            AgentLoopGuard guard,
            AutoReviewRequest request,
            TrackDecision decision,
            List<String> policyFlags,
            PurposeAnalysisTool.PurposeAnalysisResult purpose,
            List<SimulationResult> simulations,
            Long revId
    ) {
        if (!rateMeter.tryAcquire()) {
            log.warn("PreReviewAgentService: LLM_RATE_LIMITED — template fallback revId={}", revId);
            return templateSummary(decision, policyFlags);
        }
        if (!guard.acquireLlm()) {
            log.warn("PreReviewAgentService: LOOP_GUARD_HIT (LLM) — template fallback revId={}", revId);
            return templateSummary(decision, policyFlags);
        }

        try {
            String userContent = buildSummaryPrompt(decision, policyFlags, purpose, simulations);
            var llmReq = new LlmRequest(
                    PROMPT_ID, PROMPT_VER,
                    SYSTEM_PROMPT,
                    userContent,
                    256,
                    0.0
            );
            return llmClient.call(llmReq, AgentReasoningSummary.class).summary();
        } catch (LlmCallException e) {
            log.warn("PreReviewAgentService: LLM 호출 실패 — template fallback revId={}", revId, e);
            return templateSummary(decision, policyFlags);
        }
    }

    private String buildSummaryPrompt(
            TrackDecision decision,
            List<String> policyFlags,
            PurposeAnalysisTool.PurposeAnalysisResult purpose,
            List<SimulationResult> simulations
    ) {
        var sb = new StringBuilder();
        sb.append("트랙: ").append(decision.track())
          .append(" | decision_score: ").append(decision.decisionScore())
          .append(" | pd_score: ").append(decision.pd())
          .append(" | 근거: ").append(decision.rationale()).append("\n");
        sb.append("정책 경고: ").append(policyFlags.isEmpty() ? "없음" : String.join(", ", policyFlags)).append("\n");
        if (purpose != null) {
            sb.append("사유 분석: plausibility=").append(purpose.plausibility())
              .append(", redFlags=").append(purpose.redFlags()).append("\n");
        }
        if (!simulations.isEmpty()) {
            sb.append("시뮬레이션:\n");
            for (var sim : simulations) {
                sb.append("  ").append(sim.scenario())
                  .append(" → decision=").append(sim.newDecisionScore())
                  .append(", ").append(sim.result()).append("\n");
            }
        }
        return sb.toString();
    }

    private String templateSummary(TrackDecision decision, List<String> policyFlags) {
        String flagSuffix = policyFlags.isEmpty() ? "" : " 정책 경고: " + String.join(", ", policyFlags) + ".";
        return "PD %.4f, decision %.4f 기반 심사 결과 회색지대에 해당합니다.%s 심사원 검토가 권고됩니다."
                .formatted(decision.pd(), decisionScoreOrZero(decision), flagSuffix);
    }

    // ─────────────────────────────────────────────────────────────────────

    private AgentOpinion buildTrack1Opinion(AutoReviewRequest request, TrackDecision decision) {
        List<String> flags = new PolicyFlagTool(request).evaluatePolicyFlags();
        return AgentOpinion.of(
                decisionScoreOrZero(decision),
                decision.pd(),
                RiskLevelDeriver.derive(decision),
                flags,
                "PD %.4f 가 안전여유 임계 이하로 자동 승인 권고 구간입니다.".formatted(decision.pd()),
                List.of(),
                false
        );
    }

    private AgentOpinion buildTrack2Opinion(Long revId, AutoReviewRequest request, TrackDecision decision) {
        List<String> flags = new ArrayList<>(new PolicyFlagTool(request).evaluatePolicyFlags());
        flags.add("COMPLIANCE_REVIEW_REQUIRED");  // 준법 검토용 마킹 (A9)

        var draft = rejectionReasonAgentService.draft(revId, request, decision);

        return AgentOpinion.of(
                decisionScoreOrZero(decision),
                decision.pd(),
                RiskLevelDeriver.derive(decision),
                flags,
                draft.notice(),
                List.of(),
                false
        );
    }

    private AgentOpinion loopGuardFallback(Long revId) {
        log.warn("PreReviewAgentService: LOOP_GUARD_HIT revId={}", revId);
        return AgentOpinion.fallback(FallbackReason.LOOP_GUARD_HIT);
    }

    private static double decisionScoreOrZero(TrackDecision decision) {
        return decision.decisionScore() != null ? decision.decisionScore() : 0.0;
    }

    private static String buildPolicyQuery(AutoReviewRequest req) {
        String product = req.productCode() != null ? req.productCode() : "";
        String purpose = req.purposeCd() != null ? req.purposeCd() : "";
        return (product + " " + purpose + " 정책 한도 기준").trim();
    }

    private static String buildCasesQuery(AutoReviewRequest req) {
        List<String> parts = new java.util.ArrayList<>();
        if (req.productCode() != null)         parts.add(req.productCode());
        if (req.applicantSegment() != null)    parts.add(req.applicantSegment());
        if (req.dsr() != null)                 parts.add(String.format("DSR %.0f%%", req.dsr() * 100));
        if (req.ltv() != null)                 parts.add(String.format("LTV %.0f%%", req.ltv() * 100));
        if (req.creditScoreProxy() != null)    parts.add("신용점수 " + req.creditScoreProxy());
        if (req.delinquencyHistory24m() != null) parts.add("연체 " + req.delinquencyHistory24m() + "건");
        return parts.isEmpty() ? null : String.join(" ", parts) + " 유사 케이스";
    }
}
