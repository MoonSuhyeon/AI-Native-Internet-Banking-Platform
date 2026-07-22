package com.bank.ai.agent.guard;

/**
 * 에이전트 loop guard 발동 예외 — 도구 또는 LLM 호출 한도 초과.
 *
 * <p>pre-review-agent-plan.md 가드레일 §Loop.
 * {@link com.bank.ai.agent.PreReviewAgentService} 가 catch 해
 * {@code AgentOpinion.fallback(FallbackReason.LOOP_GUARD_HIT)} 로 우회.
 */
public class LoopGuardException extends RuntimeException {

    public LoopGuardException(String message) {
        super(message);
    }
}
