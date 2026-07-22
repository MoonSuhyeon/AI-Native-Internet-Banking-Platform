package com.bank.ai.agent.tools;

import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.AutoReviewResponse;
import com.bank.ai.review.service.AutoReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;

/**
 * What-if 시뮬레이션 도구 — 신청 금액·기간 변경 시 심사 점수 재계산.
 *
 * <p>pre-review-agent-plan.md §4. 단일 시나리오당 inference 2건 (decision + PD) 호출.
 * 에이전트가 최대 3개 시나리오를 선택하므로 최대 6 inference 호출.
 *
 * <p>비Spring 빈 — {@link com.bank.ai.agent.AgentToolRegistry#createToolsFor} 가
 * 요청별로 인스턴스를 생성한다.
 */
@Slf4j
@RequiredArgsConstructor
public class RecomputeWithTermsTool {

    private final AutoReviewService reviewService;
    private final AutoReviewRequest baseRequest;

    @Tool(description = """
            신청 금액 또는 상환 기간을 변경했을 때의 심사 점수를 재계산합니다.
            decision_score(P(APPROVE))와 pd_score(P(default))를 반환합니다.
            requestedAmountKw: 변경할 신청금액(만원). null이면 원래 값 유지.
            requestedPeriodMo: 변경할 상환기간(개월). null이면 원래 값 유지.
            """)
    public RecomputeResult recomputeWithTerms(Long requestedAmountKw, Integer requestedPeriodMo) {
        Long newAmount = requestedAmountKw != null ? requestedAmountKw : baseRequest.requestedAmountKw();
        int newPeriod = requestedPeriodMo != null ? requestedPeriodMo : baseRequest.requestedPeriodMo();

        log.debug("RecomputeWithTermsTool: amountKw={} periodMo={}", newAmount, newPeriod);

        AutoReviewRequest mutated = withTerms(baseRequest, newAmount, newPeriod);
        AutoReviewResponse result = reviewService.review(mutated);

        return new RecomputeResult(
                newAmount,
                newPeriod,
                result.decisionScore(),
                result.pdScore()
        );
    }

    private static AutoReviewRequest withTerms(AutoReviewRequest r, Long amountKw, int periodMo) {
        return new AutoReviewRequest(
                r.revId(), r.sex(), r.age(), r.maritalStatus(), r.militaryStatus(),
                r.familyType(), r.housingType(), r.educationLevel(), r.bachelorsField(),
                r.occupation(), r.district(), r.province(), r.applicantSegment(),
                r.incomeQuintile(), r.annualIncomeKw(), r.totalAssetKw(), r.totalDebtKw(),
                r.collateralDebtKw(), r.creditDebtKw(), r.dsr(), r.ltv(),
                r.monthlyCashflowMeanKw(), r.monthlyCashflowStdKw(),
                r.delinquencyHistory24m(), r.creditScoreProxy(),
                r.productCode(), amountKw, periodMo, r.purposeCd(), r.purposeRedFlag(),
                r.industryCd(), r.regionRiskBand(), r.nChildren(),
                r.employmentYears(), r.bureauHasRecord(), r.bureauNActive(), r.bureauMaxStatus24m()
        );
    }

    /**
     * @param mutatedAmountKw  실제 시뮬레이션에 사용된 금액 (만원)
     * @param mutatedPeriodMo  실제 시뮬레이션에 사용된 기간 (개월)
     * @param newDecisionScore 변경 후 P(APPROVE). null 이면 decision 모델 미응답.
     * @param newPdScore       변경 후 P(default_12m). null 이면 PD 모델 미배포.
     */
    public record RecomputeResult(
            Long mutatedAmountKw,
            int mutatedPeriodMo,
            Double newDecisionScore,
            Double newPdScore
    ) {
    }
}
