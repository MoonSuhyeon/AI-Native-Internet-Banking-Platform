package com.bank.ai.agent.tools;

import com.bank.ai.llm.policy.InlinePolicyIndex;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;

/**
 * 내부 정책 텍스트 조회 도구 — PolicyIndex 래퍼.
 *
 * <p>pre-review-agent-plan.md §3 PolicyLookupTool.
 * 에이전트가 citation id 로 정책 원문을 확인할 때 사용.
 * Phase 1.7 RAG 도입 시 PolicyIndex swap 으로 투명하게 교체.
 *
 * <p>비Spring 빈 — {@link com.bank.ai.agent.AgentToolRegistry#createToolsFor} 가
 * 요청별로 인스턴스를 생성한다.
 */
@RequiredArgsConstructor
public class PolicyLookupTool {

    private final InlinePolicyIndex policyIndex;

    @Tool(description = """
            정책 ID로 내부 정책 텍스트와 출처를 조회합니다.
            사용 가능한 ID 예시: PD_THRESHOLD_MATRIX_V1, MORT_DSR_LIMIT_V1,
            MORT_LTV_LIMIT_V1, CRED_SCORE_MIN_V1, DELINQ_24M_BAR_V1,
            AUTO_REVIEW_GOVERNANCE_V1, DECISION_CONFIDENCE_GUIDANCE_V1
            존재하지 않는 ID를 조회하면 오류 메시지를 반환합니다.
            """)
    public String lookupPolicy(String policyId) {
        return policyIndex.get(policyId)
                .map(entry -> entry.text() + " [출처: " + entry.source() + "]")
                .orElse("정책 ID를 찾을 수 없습니다: " + policyId);
    }
}
