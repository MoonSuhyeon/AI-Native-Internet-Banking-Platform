package com.bank.ai.agent;

import com.bank.ai.agent.tools.PolicyFlagTool;
import com.bank.ai.agent.tools.PolicyLookupTool;
import com.bank.ai.agent.tools.PurposeAnalysisTool;
import com.bank.ai.agent.tools.RecomputeWithTermsTool;
import com.bank.ai.llm.policy.InlinePolicyIndex;
import com.bank.ai.llm.purpose.PurposeAnalysisService;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.service.AutoReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 에이전트 도구 팩토리 — 요청별로 4개 read-only tool 인스턴스를 생성한다.
 *
 * <p>pre-review-agent-plan.md §A3. 도구들은 {@code @Tool} 어노테이션을 가지며
 * {@code MethodToolCallbackProvider} 에 의해 Spring AI tool calling 에 노출된다.
 *
 * <h2>read-only 제약</h2>
 * 모든 도구는 상태 변경(승인/반려/통보) 없이 조회·계산만 수행한다.
 * inference 재호출 ({@link RecomputeWithTermsTool}) 은 read-only inference 호출이며
 * DB 변경을 일으키지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentToolRegistry {

    private final AutoReviewService autoReviewService;
    private final PurposeAnalysisService purposeAnalysisService;
    private final InlinePolicyIndex policyIndex;

    /**
     * 단일 에이전트 run 에 사용할 tool 인스턴스 목록을 반환한다.
     * {@code PreReviewAgentService.run()} 호출 시마다 새로 생성 (request-scoped 상태 격리).
     *
     * @param request 현재 심사 요청 (도구 컨텍스트용)
     * @return {@code MethodToolCallbackProvider.builder().toolObjects(...)} 에 넘길 객체 목록
     */
    public List<Object> createToolsFor(AutoReviewRequest request) {
        return List.of(
                new RecomputeWithTermsTool(autoReviewService, request),
                new PolicyFlagTool(request),
                new PurposeAnalysisTool(purposeAnalysisService, request),
                new PolicyLookupTool(policyIndex)
        );
    }
}
