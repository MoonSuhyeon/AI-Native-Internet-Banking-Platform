package com.bank.ai.agent.tools;

import com.bank.ai.review.dto.AutoReviewRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;

import java.util.ArrayList;
import java.util.List;

/**
 * 정책 소프트 경고 플래그 평가 도구 — hard constraint 위반 이전의 경계 신호 감지.
 *
 * <p>pre-review-agent-plan.md §3 PolicyFlagTool.
 * Hard constraint 는 HardConstraintEvaluator 가 이미 판단 — 본 도구는 그 하위의
 * 주의 임계(80% 기준) 이상 소프트 경보만 반환한다.
 *
 * <p>비Spring 빈 — {@link com.bank.ai.agent.AgentToolRegistry#createToolsFor} 가
 * 요청별로 인스턴스를 생성한다.
 */
@RequiredArgsConstructor
public class PolicyFlagTool {

    /** DSR 소프트 경보 임계 — hard limit(40%) × 80% */
    private static final double DSR_WARNING_THRESHOLD = 0.32;
    /** LTV 소프트 경보 임계 — hard limit(70%) × 80% */
    private static final double LTV_WARNING_THRESHOLD = 0.56;
    /** 신용점수 소프트 경보 임계 — hard floor(600) + 60점 완충 */
    private static final int CREDIT_SCORE_WARNING_THRESHOLD = 660;

    private final AutoReviewRequest request;

    @Tool(description = """
            현재 신청 조건에 대한 정책 소프트 경고 플래그 목록을 반환합니다.
            hard constraint 위반은 아니지만 주의가 필요한 항목을 식별합니다.
            반환 가능한 플래그: DSR_THRESHOLD_WARNING, LTV_THRESHOLD_WARNING,
            LOW_CREDIT_SCORE_WARNING, HIGH_DEBT_RATIO_WARNING, VAGUE_PURPOSE_WARNING
            """)
    public List<String> evaluatePolicyFlags() {
        List<String> flags = new ArrayList<>();

        if (request.dsr() != null && request.dsr() >= DSR_WARNING_THRESHOLD) {
            flags.add("DSR_THRESHOLD_WARNING");
        }
        if (request.ltv() != null && request.ltv() >= LTV_WARNING_THRESHOLD) {
            flags.add("LTV_THRESHOLD_WARNING");
        }
        if (request.creditScoreProxy() != null
                && request.creditScoreProxy() < CREDIT_SCORE_WARNING_THRESHOLD) {
            flags.add("LOW_CREDIT_SCORE_WARNING");
        }
        if (request.annualIncomeKw() != null && request.totalDebtKw() != null
                && request.totalDebtKw() > request.annualIncomeKw() * 5) {
            flags.add("HIGH_DEBT_RATIO_WARNING");
        }
        if (Boolean.TRUE.equals(request.purposeRedFlag())) {
            flags.add("VAGUE_PURPOSE_WARNING");
        }
        return flags;
    }
}
