package com.bank.ai.agent.tools;

import com.bank.ai.llm.purpose.PurposeAnalysis;
import com.bank.ai.llm.purpose.PurposeAnalysisInput;
import com.bank.ai.llm.purpose.PurposeAnalysisService;
import com.bank.ai.review.dto.AutoReviewRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;

/**
 * 신청 사유 분석 도구 — PurposeAnalysisService 래퍼.
 *
 * <p>pre-review-agent-plan.md §3 PurposeAnalysisTool.
 * 에이전트가 특정 purposeText 를 넘기면 plausibility · specificity · red flag 반환.
 * 텍스트 없이 호출하면 base request 의 purposeCd 로 분석.
 *
 * <p>비Spring 빈 — {@link com.bank.ai.agent.AgentToolRegistry#createToolsFor} 가
 * 요청별로 인스턴스를 생성한다.
 */
@Slf4j
@RequiredArgsConstructor
public class PurposeAnalysisTool {

    private final PurposeAnalysisService purposeAnalysisService;
    private final AutoReviewRequest baseRequest;

    @Tool(description = """
            신청 사유 텍스트의 신뢰도(plausibility)와 구체성(specificity)을 분석합니다.
            purposeText: 분석할 사유 텍스트. null이면 신청서의 purpose_cd를 사용합니다.
            반환: plausibility(0~1), specificity(0~1), redFlags(위험 신호 코드 목록)
            """)
    public PurposeAnalysisResult analyzePurpose(String purposeText) {
        String text = purposeText != null ? purposeText : baseRequest.purposeCd();
        log.debug("PurposeAnalysisTool: purposeText={}", text);

        String personaSummary = buildPersonaSummary(baseRequest);
        PurposeAnalysisInput input = new PurposeAnalysisInput(
                personaSummary, text,
                baseRequest.productCode(),
                baseRequest.requestedAmountKw(),
                baseRequest.requestedPeriodMo()
        );

        PurposeAnalysis result = purposeAnalysisService.analyze(input);
        List<String> flags = result.redFlags().stream()
                .map(PurposeAnalysis.RedFlag::name)
                .toList();
        return new PurposeAnalysisResult(result.plausibility(), result.specificity(), flags);
    }

    private static String buildPersonaSummary(AutoReviewRequest r) {
        return "%s / %s / Q%s / %s세".formatted(
                r.applicantSegment() != null ? r.applicantSegment() : "unknown",
                r.occupation()       != null ? r.occupation()       : "unknown",
                r.incomeQuintile()   != null ? r.incomeQuintile()   : "?",
                r.age()              != null ? r.age()              : "?"
        );
    }

    /**
     * @param plausibility 신청 사유 신뢰도 (0~1)
     * @param specificity  신청 사유 구체성 (0~1)
     * @param redFlags     검출된 위험 신호 코드 목록
     */
    public record PurposeAnalysisResult(
            double plausibility,
            double specificity,
            List<String> redFlags
    ) {
    }
}
