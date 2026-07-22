package com.bank.ai.agent.guard;

/**
 * 단일 에이전트 run 내 도구·LLM 호출 횟수를 추적하는 루프 가드.
 *
 * <p>pre-review-agent-plan.md §가드레일 — Loop: 도구 호출 ≤ 6, LLM 호출 ≤ 2.
 * Not a Spring bean — {@code PreReviewAgentService.run()} 마다 새로 생성.
 */
public class AgentLoopGuard {

    private final int maxToolCalls;
    private final int maxLlmCalls;
    private int toolCallCount = 0;
    private int llmCallCount = 0;

    public AgentLoopGuard(int maxToolCalls, int maxLlmCalls) {
        this.maxToolCalls = maxToolCalls;
        this.maxLlmCalls = maxLlmCalls;
    }

    /**
     * 도구 호출 슬롯 선점.
     *
     * @return true 면 허용, false 면 한도 도달 (LOOP_GUARD_HIT fallback 처리 필요)
     */
    public boolean acquireTool() {
        if (toolCallCount >= maxToolCalls) return false;
        toolCallCount++;
        return true;
    }

    /**
     * LLM 호출 슬롯 선점.
     *
     * @return true 면 허용, false 면 한도 도달 (template fallback 처리 필요)
     */
    public boolean acquireLlm() {
        if (llmCallCount >= maxLlmCalls) return false;
        llmCallCount++;
        return true;
    }

    public int getToolCallCount() { return toolCallCount; }
    public int getLlmCallCount() { return llmCallCount; }
    public boolean isToolLimitReached() { return toolCallCount >= maxToolCalls; }
    public boolean isLlmLimitReached() { return llmCallCount >= maxLlmCalls; }
}
