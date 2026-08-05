package com.bank.harness.audit;

import java.util.Optional;

/**
 * 에이전트 감사 기록 저장소.
 *
 * <p>기록은 <b>추가만 가능</b>하다. 수정·삭제 경로를 두지 않는 것이 이 인터페이스의 요점이다 —
 * 감사 기록을 고칠 수 있으면 그것은 감사가 아니다. DB 차원에서도 트리거로 막는다
 * ({@link AuditImmutabilityVerifier}).
 */
public interface AgentAuditLog {

    /**
     * 판단 1건을 남긴다.
     *
     * <p>구현은 호출자의 트랜잭션과 분리해야 한다. 판단이 롤백되더라도
     * "그런 판단을 시도했다"는 사실은 남아야 하기 때문이다.
     */
    void record(AgentAuditEntry entry);

    /** 대상의 가장 최근 기록. */
    Optional<AgentAuditEntry> findLatest(String subjectType, String subjectId);
}
