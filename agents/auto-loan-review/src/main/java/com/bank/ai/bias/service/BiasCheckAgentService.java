package com.bank.ai.bias.service;

import com.bank.ai.bias.dto.BiasCheckPayload;
import com.bank.ai.bias.dto.BiasReportCallbackRequest;
import com.bank.ai.llm.client.LlmCallException;
import com.bank.ai.llm.client.LlmClient;
import com.bank.ai.llm.client.LlmRequest;
import com.bank.ai.llm.support.LlmRequestRateMeter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 편향 검증 에이전트 — BIAS_CHECK_REQUESTED 이벤트 처리.
 *
 * <p>분석 흐름:
 * <ol>
 *   <li>규칙 기반 이상 탐지 — CB/DSR 데이터 vs 심사원 결정 불일치 플래그</li>
 *   <li>LLM 요약 — 발견된 이상 지점을 1~2문장 한국어로 설명 (rate limit 시 template fallback)</li>
 *   <li>심각도 판정 — NONE / LOW / MEDIUM / HIGH / BLOCKED</li>
 * </ol>
 *
 * <p>심각도 기준:
 * <ul>
 *   <li>BLOCKED — DSR 한도 초과인데 승인, 또는 CB REJECT인데 승인</li>
 *   <li>HIGH    — 고신용(CB≥750)인데 이유 불명 거절, 또는 2개 이상 이상 플래그</li>
 *   <li>MEDIUM  — 1개 이상 플래그 (설명 가능한 수준)</li>
 *   <li>LOW     — 경미한 불일치 또는 단순 정책 확인 필요</li>
 *   <li>NONE    — 이상 없음</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BiasCheckAgentService {

    static final String PROMPT_ID  = "bias_check_summary";
    static final int    PROMPT_VER = 1;
    static final String MODEL_NAME = "bias-check-v1";

    private static final String SYSTEM_PROMPT =
            "당신은 대출 심사 편향 분석 전문가입니다. " +
            "제공된 심사 결과와 이상 지점을 바탕으로 1~2문장의 한국어 편향 위험도 요약을 작성하세요. " +
            "수치 근거를 포함하고, 사실 기반으로 서술하세요.";

    private final LlmClient llmClient;
    private final LlmRequestRateMeter rateMeter;

    /**
     * 편향 분석을 실행하고 콜백 바디를 반환한다.
     */
    public BiasReportCallbackRequest analyze(BiasCheckPayload payload) {
        Instant start = Instant.now();

        BiasCheckPayload.ReviewerDecision decision = payload.reviewerDecision();
        BiasCheckPayload.ReviewContext    context  = payload.context();

        boolean approved = "APPROVED".equalsIgnoreCase(decision.decisionCd());

        // ── 1. 규칙 기반 이상 탐지 ──────────────────────────────────────────
        List<BiasReportCallbackRequest.Finding> findings = new ArrayList<>();

        // F1: DSR 한도 초과인데 승인
        if (approved && isDsrExceeded(context)) {
            findings.add(finding("DSR_LIMIT_EXCEEDED_BUT_APPROVED", "FAIL",
                    "DSR %s%% > 한도 %s%% 임에도 승인 처리".formatted(
                            bpsToPercent(context.dsrRatioBps()),
                            bpsToPercent(context.dsrLimitBps()))));
        }

        // F2: CB REJECT인데 승인
        if (approved && "REJECT".equalsIgnoreCase(context.cbDecisionCd())) {
            findings.add(finding("CB_REJECT_OVERRIDDEN", "FAIL",
                    "CB 기관 결정이 REJECT임에도 심사원이 승인"));
        }

        // F3: 고신용(CB≥750)인데 거절, 사유가 신용 무관
        if (!approved && isHighCreditScore(context) && isNonCreditRejectReason(decision.rejectReasonCd())) {
            findings.add(finding("HIGH_CREDIT_SCORE_REJECTED_NON_CREDIT", "WARN",
                    "CB 점수 %d(≥750)이지만 신용 외 사유(%s)로 거절".formatted(
                            context.cbScore(), decision.rejectReasonCd())));
        }

        // F4: DSR 여유 충분한데 거절 (DSR이 한도의 60% 미만)
        if (!approved && isDsrWellBelowLimit(context) && "CREDIT_SCORE".equalsIgnoreCase(decision.rejectReasonCd())) {
            findings.add(finding("LOW_DSR_REJECTED_FOR_CREDIT", "WARN",
                    "DSR %s%%로 한도 대비 충분한 여유 있음에도 신용 점수 사유 거절".formatted(
                            bpsToPercent(context.dsrRatioBps()))));
        }

        // F5: 저신용(CB<600)인데 승인
        if (approved && isLowCreditScore(context)) {
            findings.add(finding("LOW_CREDIT_SCORE_APPROVED", "WARN",
                    "CB 점수 %d(<600) 임에도 승인 — 우대 조건 없을 경우 편향 가능성".formatted(
                            context.cbScore())));
        }

        // ── 2. 심각도 판정 ──────────────────────────────────────────────────
        String severityCd = deriveSeverity(findings);

        // ── 3. LLM 요약 (rate limit 시 template fallback) ───────────────────
        long elapsedMs = Instant.now().toEpochMilli() - start.toEpochMilli();
        String summary = generateSummary(payload, findings, severityCd, elapsedMs);

        int latencyMs = (int) (Instant.now().toEpochMilli() - start.toEpochMilli());

        log.info("BiasCheckAgentService: revId={} severity={} findings={} latencyMs={}",
                payload.revId(), severityCd, findings.size(), latencyMs);

        return new BiasReportCallbackRequest(
                severityCd,
                summary,
                findings,
                MODEL_NAME,
                "1.0",
                null,
                null,
                null,
                latencyMs
        );
    }

    // ── private helpers ─────────────────────────────────────────────────────

    private String deriveSeverity(List<BiasReportCallbackRequest.Finding> findings) {
        boolean hasBlock = findings.stream().anyMatch(f -> "FAIL".equals(f.result()) &&
                (f.code().contains("DSR_LIMIT_EXCEEDED") || f.code().contains("CB_REJECT_OVERRIDDEN")));
        if (hasBlock) return "BLOCKED";

        long failCount = findings.stream().filter(f -> "FAIL".equals(f.result())).count();
        long warnCount = findings.stream().filter(f -> "WARN".equals(f.result())).count();

        if (failCount >= 1) return "HIGH";
        if (warnCount >= 2) return "HIGH";
        if (warnCount == 1) return "MEDIUM";
        if (!findings.isEmpty()) return "LOW";
        return "NONE";
    }

    private String generateSummary(BiasCheckPayload payload,
                                   List<BiasReportCallbackRequest.Finding> findings,
                                   String severityCd,
                                   long ruleElapsedMs) {
        if (findings.isEmpty()) {
            return "CB/DSR 데이터와 심사원 결정 간 이상 패턴이 탐지되지 않았습니다.";
        }

        if (!rateMeter.tryAcquire()) {
            log.warn("BiasCheckAgentService: LLM_RATE_LIMITED — template fallback revId={}", payload.revId());
            return templateSummary(severityCd, findings);
        }

        try {
            String userContent = buildPrompt(payload, findings, severityCd);
            var req = new LlmRequest(PROMPT_ID, PROMPT_VER, SYSTEM_PROMPT, userContent, 256, 0.0);
            var result = llmClient.call(req, BiasCheckSummaryOutput.class);
            return result.summary();
        } catch (LlmCallException e) {
            log.warn("BiasCheckAgentService: LLM 호출 실패 — template fallback revId={}", payload.revId(), e);
            return templateSummary(severityCd, findings);
        }
    }

    private String buildPrompt(BiasCheckPayload p,
                               List<BiasReportCallbackRequest.Finding> findings,
                               String severityCd) {
        var ctx = p.context();
        var dec = p.reviewerDecision();
        var sb = new StringBuilder();
        sb.append("심사원 결정: ").append(dec.decisionCd())
          .append(" | 거절사유: ").append(dec.rejectReasonCd() != null ? dec.rejectReasonCd() : "없음").append("\n");
        sb.append("CB 결정: ").append(ctx.cbDecisionCd())
          .append(" | CB 점수: ").append(ctx.cbScore()).append("\n");
        sb.append("DSR: ").append(bpsToPercent(ctx.dsrRatioBps()))
          .append("% / 한도: ").append(bpsToPercent(ctx.dsrLimitBps())).append("%\n");
        sb.append("감지된 이상 (심각도=").append(severityCd).append("):\n");
        for (var f : findings) {
            sb.append("  [").append(f.result()).append("] ").append(f.code())
              .append(": ").append(f.detail()).append("\n");
        }
        return sb.toString();
    }

    private String templateSummary(String severityCd, List<BiasReportCallbackRequest.Finding> findings) {
        String codes = findings.stream().map(BiasReportCallbackRequest.Finding::code)
                .reduce((a, b) -> a + ", " + b).orElse("없음");
        return "편향 심각도 %s — 이상 항목: %s. 심사원 결정과 CB/DSR 데이터 간 불일치가 탐지되었습니다. 검토가 필요합니다."
                .formatted(severityCd, codes);
    }

    private static boolean isDsrExceeded(BiasCheckPayload.ReviewContext ctx) {
        return ctx.dsrRatioBps() != null && ctx.dsrLimitBps() != null
                && ctx.dsrRatioBps() > ctx.dsrLimitBps();
    }

    private static boolean isDsrWellBelowLimit(BiasCheckPayload.ReviewContext ctx) {
        if (ctx.dsrRatioBps() == null || ctx.dsrLimitBps() == null) return false;
        return ctx.dsrRatioBps() < ctx.dsrLimitBps() * 0.6;
    }

    private static boolean isHighCreditScore(BiasCheckPayload.ReviewContext ctx) {
        return ctx.cbScore() != null && ctx.cbScore() >= 750;
    }

    private static boolean isLowCreditScore(BiasCheckPayload.ReviewContext ctx) {
        return ctx.cbScore() != null && ctx.cbScore() < 600;
    }

    private static boolean isNonCreditRejectReason(String rejectReasonCd) {
        if (rejectReasonCd == null) return false;
        return !rejectReasonCd.contains("CREDIT") && !rejectReasonCd.contains("SCORE");
    }

    private static String bpsToPercent(Integer bps) {
        if (bps == null) return "?";
        return "%.1f".formatted(bps / 100.0);
    }

    private static BiasReportCallbackRequest.Finding finding(String code, String result, String detail) {
        return new BiasReportCallbackRequest.Finding(code, result, detail);
    }

    /** LLM 출력 구조체 — JSON 역직렬화용 */
    public record BiasCheckSummaryOutput(String summary) {}
}
