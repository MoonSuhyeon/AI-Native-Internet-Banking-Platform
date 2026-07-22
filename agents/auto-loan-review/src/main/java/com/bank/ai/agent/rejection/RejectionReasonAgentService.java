package com.bank.ai.agent.rejection;

import com.bank.ai.llm.client.LlmCallException;
import com.bank.ai.llm.client.LlmClient;
import com.bank.ai.llm.client.LlmRequest;
import com.bank.ai.llm.config.AgentProperties;
import com.bank.ai.llm.support.LlmRequestRateMeter;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.rule.domain.HardFailReason;
import com.bank.ai.rule.domain.TrackDecision;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Track 2 거절 통보문 초안 생성 — pre-review-agent-plan.md §아키텍처 P3 (A8).
 *
 * <p>흐름:
 * <ol>
 *   <li>거절 사유 코드 도출 (hard fail 코드 또는 PD_THRESHOLD_EXCEEDED)</li>
 *   <li>kill switch / rate limit 확인</li>
 *   <li>LLM 호출 → {@link RejectionNoticeDraft} (구조화 출력)</li>
 *   <li>실패 시 템플릿 폴백</li>
 * </ol>
 *
 * <p>A9에서 {@code AutoReviewEventListener} 에 트리거 연결 예정.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RejectionReasonAgentService {

    static final String PROMPT_ID = "rejection_reason_draft";
    static final int PROMPT_VER = 1;

    private static final String SYSTEM_PROMPT =
            "당신은 대출 심사 거절 통보 전문가입니다. " +
            "제공된 거절 사유를 바탕으로 고객에게 직접 전달될 1~2문장의 한국어 거절 통보문을 작성하세요. " +
            "법적 요건(여신전문금융업법 §17)을 준수하고, 구체적 사유를 명시하세요.";

    private final AgentProperties agentProps;
    private final LlmRequestRateMeter rateMeter;
    private final LlmClient llmClient;

    /**
     * @param revId    loan_review PK (로그·멱등성용)
     * @param request  자동심사 입력 (product, segment 등)
     * @param decision RuleEngine Track 2 결정 결과
     * @return RejectionDraft — fallbackReason != null 이면 LLM 미사용
     */
    public RejectionDraft draft(Long revId, AutoReviewRequest request, TrackDecision decision) {
        List<String> reasonCodes = deriveReasonCodes(decision);

        if (!agentProps.enabled()) {
            log.info("RejectionReasonAgentService: kill switch — AGENT_DISABLED revId={}", revId);
            return new RejectionDraft(templateNotice(decision, reasonCodes), reasonCodes, "AGENT_DISABLED");
        }

        if (!rateMeter.tryAcquire()) {
            log.warn("RejectionReasonAgentService: LLM_RATE_LIMITED revId={}", revId);
            return new RejectionDraft(templateNotice(decision, reasonCodes), reasonCodes, "LLM_RATE_LIMITED");
        }

        try {
            String userContent = buildPrompt(request, decision, reasonCodes);
            var llmReq = new LlmRequest(PROMPT_ID, PROMPT_VER, SYSTEM_PROMPT, userContent, 256, 0.0);
            String notice = llmClient.call(llmReq, RejectionNoticeDraft.class).notice();
            log.info("RejectionReasonAgentService: 완료 revId={} reasonCodes={}", revId, reasonCodes);
            return new RejectionDraft(notice, reasonCodes, null);
        } catch (LlmCallException e) {
            log.warn("RejectionReasonAgentService: LLM 호출 실패 — template fallback revId={}", revId, e);
            return new RejectionDraft(templateNotice(decision, reasonCodes), reasonCodes, "TOOL_ERROR");
        }
    }

    // ─────────────────────────────────────────────────────────────────────

    /**
     * Hard fail 코드 우선, 없으면 PD 초과로 간주.
     * Track 2 진입 시 hardFails 가 비어 있으면 PD > pdThreshold 또는 decisionScore ≤ decReject 경로.
     */
    static List<String> deriveReasonCodes(TrackDecision decision) {
        if (decision.hardFails() != null && !decision.hardFails().isEmpty()) {
            return decision.hardFails().stream().map(HardFailReason::code).toList();
        }
        return List.of("PD_THRESHOLD_EXCEEDED");
    }

    private String buildPrompt(AutoReviewRequest request, TrackDecision decision, List<String> reasonCodes) {
        return "상품: %s | 세그먼트: %s | 거절사유: %s | 정책근거: %s"
                .formatted(
                        request.productCode(),
                        request.applicantSegment(),
                        String.join(", ", reasonCodes),
                        decision.rationale()
                );
    }

    private String templateNotice(TrackDecision decision, List<String> reasonCodes) {
        String reasons = reasonCodes.isEmpty() ? decision.rationale() : String.join(", ", reasonCodes);
        return "귀하의 대출 신청은 자행 신용정책 기준에 따라 반려되었습니다. 주요 사유: %s.".formatted(reasons);
    }
}
