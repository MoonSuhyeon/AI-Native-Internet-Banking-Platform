package com.bank.harness.audit;

import java.util.Optional;

/**
 * 감사를 끈 환경용 구현.
 *
 * <p>추적의 {@code NoOpAgentTracer} 와 같은 이유로 존재한다. 감사를 끄면 빈이 아예 없어지는
 * 구조였을 때, 감사와 무관한 테스트(드리프트·공정성)가 의존성 부재로 깨졌다.
 * <b>기능을 끄는 것이 의존하는 쪽을 깨뜨리면 안 된다.</b>
 */
public class NoOpAgentAuditLog implements AgentAuditLog {

    @Override
    public void record(AgentAuditEntry entry) {
        // no-op
    }

    @Override
    public Optional<AgentAuditEntry> findLatest(String subjectType, String subjectId) {
        return Optional.empty();
    }

    @Override
    public Optional<AgentAuditEntry> findLatest(String subjectType, String subjectId,
                                                String decisionKind) {
        return Optional.empty();
    }
}
