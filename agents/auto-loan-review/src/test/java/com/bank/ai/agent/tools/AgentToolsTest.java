package com.bank.ai.agent.tools;

import com.bank.ai.llm.policy.InlinePolicyIndex;
import com.bank.ai.llm.policy.PolicyIndex;
import com.bank.ai.llm.purpose.PurposeAnalysis;
import com.bank.ai.llm.purpose.PurposeAnalysisInput;
import com.bank.ai.llm.purpose.PurposeAnalysisService;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.AutoReviewResponse;
import com.bank.ai.review.service.AutoReviewService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A3 4개 read-only tool 단위 테스트.
 *
 * <p>실제 inference 호출 없이 mock 으로 검증.
 * tool method 시그니처·반환 타입이 Spring AI @Tool 스키마 생성 규약을 따르는지 확인.
 */
class AgentToolsTest {

    // ── 공통 픽스처 ─────────────────────────────────────────────

    private static AutoReviewRequest baseRequest() {
        return new AutoReviewRequest(
                1L,
                "M", 35, "SINGLE", "NONE", "1인가구", "SELF",
                "BACHELOR", "COMPUTER", "SW개발자", "강남구", "서울",
                "REGULAR",
                3, 5_000L, 20_000L, 3_000L, 0L, 3_000L,
                0.35, 0.55, 200L, 50L, 0, 640,
                "CRED_001", 3_000L, 36, "LIVING", false,
                "IT", 2, 0, 3, true, 1, 0
        );
    }

    // ── PolicyFlagTool ─────────────────────────────────────────

    @Test
    void PolicyFlagTool_DSR경고_감지() {
        var tool = new PolicyFlagTool(baseRequest()); // dsr=0.35 >= 0.32
        List<String> flags = tool.evaluatePolicyFlags();
        assertThat(flags).contains("DSR_THRESHOLD_WARNING");
    }

    @Test
    void PolicyFlagTool_LTV경고_없음() {
        var tool = new PolicyFlagTool(baseRequest()); // ltv=0.55 < 0.56
        List<String> flags = tool.evaluatePolicyFlags();
        assertThat(flags).doesNotContain("LTV_THRESHOLD_WARNING");
    }

    @Test
    void PolicyFlagTool_신용점수경고_감지() {
        var tool = new PolicyFlagTool(baseRequest()); // creditScoreProxy=640 < 660
        List<String> flags = tool.evaluatePolicyFlags();
        assertThat(flags).contains("LOW_CREDIT_SCORE_WARNING");
    }

    @Test
    void PolicyFlagTool_목적플래그_없음() {
        var tool = new PolicyFlagTool(baseRequest()); // purposeRedFlag=false
        List<String> flags = tool.evaluatePolicyFlags();
        assertThat(flags).doesNotContain("VAGUE_PURPOSE_WARNING");
    }

    // ── PolicyLookupTool ───────────────────────────────────────

    @Test
    void PolicyLookupTool_존재하는_ID_텍스트_반환() {
        var policy = new InlinePolicyIndex(Map.of(
                "DSR_LIMIT_V1", new PolicyIndex.PolicyEntry("DSR 40% 한도", "policy_2026q2")
        ));
        var tool = new PolicyLookupTool(policy);
        String result = tool.lookupPolicy("DSR_LIMIT_V1");
        assertThat(result).contains("DSR 40% 한도").contains("policy_2026q2");
    }

    @Test
    void PolicyLookupTool_없는_ID_오류메시지_반환() {
        var policy = new InlinePolicyIndex(Map.of());
        var tool = new PolicyLookupTool(policy);
        String result = tool.lookupPolicy("UNKNOWN_POLICY");
        assertThat(result).contains("UNKNOWN_POLICY");
    }

    // ── PurposeAnalysisTool ────────────────────────────────────

    @Test
    void PurposeAnalysisTool_서비스_호출_후_결과_반환() {
        var mockService = mock(PurposeAnalysisService.class);
        when(mockService.analyze(any(PurposeAnalysisInput.class)))
                .thenReturn(new PurposeAnalysis(0.8, 0.7, List.of(), "ok"));

        var tool = new PurposeAnalysisTool(mockService, baseRequest());
        var result = tool.analyzePurpose("주택 구입 자금");

        assertThat(result.plausibility()).isEqualTo(0.8);
        assertThat(result.specificity()).isEqualTo(0.7);
        assertThat(result.redFlags()).isEmpty();
        verify(mockService).analyze(any(PurposeAnalysisInput.class));
    }

    @Test
    void PurposeAnalysisTool_null입력시_purposeCd_사용() {
        var mockService = mock(PurposeAnalysisService.class);
        when(mockService.analyze(any())).thenReturn(new PurposeAnalysis(0.5, 0.5, List.of(), "fallback"));

        var tool = new PurposeAnalysisTool(mockService, baseRequest()); // purposeCd="LIVING"
        tool.analyzePurpose(null);

        verify(mockService).analyze(any(PurposeAnalysisInput.class));
    }

    // ── RecomputeWithTermsTool ─────────────────────────────────

    @Test
    void RecomputeWithTermsTool_금액변경후_점수_반환() {
        var mockService = mock(AutoReviewService.class);
        when(mockService.review(any())).thenReturn(
                new AutoReviewResponse("v1", "APPROVE", 0.84,
                        Map.of("APPROVE", 0.84, "REJECT", 0.16), 0.09, "pd_v1")
        );

        var tool = new RecomputeWithTermsTool(mockService, baseRequest());
        var result = tool.recomputeWithTerms(2_400L, null); // 금액 20% 감소

        assertThat(result.mutatedAmountKw()).isEqualTo(2_400L);
        assertThat(result.mutatedPeriodMo()).isEqualTo(36); // 원래 값 유지
        assertThat(result.newDecisionScore()).isEqualTo(0.84);
        assertThat(result.newPdScore()).isEqualTo(0.09);
        verify(mockService).review(any());
    }

    @Test
    void RecomputeWithTermsTool_null_파라미터는_원래값_유지() {
        var mockService = mock(AutoReviewService.class);
        when(mockService.review(any())).thenReturn(
                new AutoReviewResponse("v1", "APPROVE", 0.72,
                        Map.of("APPROVE", 0.72, "REJECT", 0.28), 0.18, "pd_v1")
        );

        var req = baseRequest();
        var tool = new RecomputeWithTermsTool(mockService, req);
        var result = tool.recomputeWithTerms(null, null);

        assertThat(result.mutatedAmountKw()).isEqualTo(req.requestedAmountKw());
        assertThat(result.mutatedPeriodMo()).isEqualTo(req.requestedPeriodMo());
    }
}
