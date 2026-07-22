package com.bank.ai.agent.rejection;

import java.util.List;

/**
 * 거절 통보문 초안 — pre-review-agent-plan.md §아키텍처 rejection/.
 *
 * @param notice        고객 전달용 거절 사유 (LLM 생성 또는 템플릿)
 * @param reasonCodes   머신 판독용 거절 사유 코드 (HardFailReason.code 또는 "PD_THRESHOLD_EXCEEDED")
 * @param fallbackReason null = LLM 정상 생성; 비정상 시 "AGENT_DISABLED" / "LLM_RATE_LIMITED" / "TOOL_ERROR"
 */
public record RejectionDraft(
        String notice,
        List<String> reasonCodes,
        String fallbackReason
) {
    public boolean isFallback() {
        return fallbackReason != null;
    }
}
