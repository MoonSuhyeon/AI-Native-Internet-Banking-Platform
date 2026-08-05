package com.bank.ai.llm.policy;

import com.bank.harness.validation.CitationIndex;

import java.util.Optional;

/**
 * 정책 인덱스 인터페이스 — phase-d-rag.md D2-1.
 *
 * <p>Phase 1.6 까지는 {@link InlinePolicyIndex} (application.yml 인라인) 가 단독 구현.
 * {@code ai.rag.enabled=true} 시 {@link com.bank.ai.rag.policy.RagPolicyIndex} 가 추가 등록되며,
 * {@link com.bank.harness.validation.CitationResolver} 가 citation id prefix
 * ({@code inline:} / {@code rag:}) 로 구현체를 선택.
 *
 * <p><b>실존 확인({@code exists})은 {@link CitationIndex} 가 정의한다.</b>
 * "인용한 근거가 실제로 있는가"는 대출 지식이 아니라 환각 방지의 일반 규칙이라
 * 하네스가 소유한다 (docs/decisions/agent-harness-consolidation.md 4단계).
 * 여기 남는 {@link #get} 은 정책 <b>본문</b>을 다루므로 도메인 몫이다.
 */
public interface PolicyIndex extends CitationIndex {

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
