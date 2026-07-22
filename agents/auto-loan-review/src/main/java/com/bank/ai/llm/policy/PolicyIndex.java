package com.bank.ai.llm.policy;

import java.util.Optional;

/**
 * 정책 인덱스 인터페이스 — phase-d-rag.md D2-1.
 *
 * <p>Phase 1.6 까지는 {@link InlinePolicyIndex} (application.yml 인라인) 가 단독 구현.
 * {@code ai.rag.enabled=true} 시 {@link com.bank.ai.rag.policy.RagPolicyIndex} 가 추가 등록되며,
 * GroundingValidator 는 citation id prefix ({@code inline:} / {@code rag:}) 로 구현체를 선택.
 */
public interface PolicyIndex {

    /** id 가 인덱스에 존재하는지 확인. */
    boolean exists(String id);

    /** id 에 대응하는 정책 항목 반환. 없으면 {@link Optional#empty()}. */
    Optional<PolicyEntry> get(String id);

    /**
     * @param text   심사원·LLM 노출용 정책 본문
     * @param source 출처 식별자 (예: "internal_policy_2026q2")
     */
    record PolicyEntry(String text, String source) {

        public PolicyEntry {
            text = text != null ? text : "";
            source = source != null ? source : "";
        }
    }
}
