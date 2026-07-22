package com.bank.ai.agent.guard;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentLoopGuard 단위 테스트.
 */
class AgentLoopGuardTest {

    @Test
    void 초기상태_카운트_0() {
        var guard = new AgentLoopGuard(6, 2);
        assertThat(guard.getToolCallCount()).isZero();
        assertThat(guard.getLlmCallCount()).isZero();
        assertThat(guard.isToolLimitReached()).isFalse();
        assertThat(guard.isLlmLimitReached()).isFalse();
    }

    @Test
    void 도구_호출_한도_내_acquireTool_성공() {
        var guard = new AgentLoopGuard(3, 2);
        assertThat(guard.acquireTool()).isTrue();
        assertThat(guard.acquireTool()).isTrue();
        assertThat(guard.acquireTool()).isTrue();
        assertThat(guard.getToolCallCount()).isEqualTo(3);
    }

    @Test
    void 도구_호출_한도_초과시_acquireTool_false() {
        var guard = new AgentLoopGuard(2, 2);
        guard.acquireTool();
        guard.acquireTool();

        assertThat(guard.acquireTool()).isFalse();
        assertThat(guard.getToolCallCount()).isEqualTo(2); // 초과 후 카운트 증가 없음
        assertThat(guard.isToolLimitReached()).isTrue();
    }

    @Test
    void LLM_호출_한도_내_acquireLlm_성공() {
        var guard = new AgentLoopGuard(6, 2);
        assertThat(guard.acquireLlm()).isTrue();
        assertThat(guard.acquireLlm()).isTrue();
        assertThat(guard.getLlmCallCount()).isEqualTo(2);
    }

    @Test
    void LLM_호출_한도_초과시_acquireLlm_false() {
        var guard = new AgentLoopGuard(6, 1);
        guard.acquireLlm();

        assertThat(guard.acquireLlm()).isFalse();
        assertThat(guard.getLlmCallCount()).isEqualTo(1);
        assertThat(guard.isLlmLimitReached()).isTrue();
    }

    @Test
    void 도구_LLM_카운터_독립_동작() {
        var guard = new AgentLoopGuard(6, 2);
        guard.acquireTool();
        guard.acquireTool();
        guard.acquireLlm();

        assertThat(guard.getToolCallCount()).isEqualTo(2);
        assertThat(guard.getLlmCallCount()).isEqualTo(1);
        assertThat(guard.isToolLimitReached()).isFalse();
        assertThat(guard.isLlmLimitReached()).isFalse();
    }

    @Test
    void 한도_1일때_즉시_차단() {
        var guard = new AgentLoopGuard(1, 1);
        assertThat(guard.acquireTool()).isTrue();
        assertThat(guard.acquireTool()).isFalse();

        assertThat(guard.acquireLlm()).isTrue();
        assertThat(guard.acquireLlm()).isFalse();
    }
}
