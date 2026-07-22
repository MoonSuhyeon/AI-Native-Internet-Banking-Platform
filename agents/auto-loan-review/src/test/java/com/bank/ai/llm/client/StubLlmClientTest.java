package com.bank.ai.llm.client;

import com.bank.ai.llm.purpose.PurposeAnalysis;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StubLlmClient 결정론적 분기 검증 — PII 마스킹 / 서비스 통합 없이 stub 자체만.
 */
class StubLlmClientTest {

    private final StubLlmClient stub = new StubLlmClient(new ObjectMapper());

    private LlmRequest req(String userContent) {
        return new LlmRequest(
                "purpose_analysis", 1,
                "system",
                userContent,
                256, 0.0
        );
    }

    @Test
    void purpose_analysis_정상_사유는_높은_점수() {
        var result = stub.call(req("강남구 아파트 매매 자금 1억 5천 필요합니다 잔금 6월"),
                                PurposeAnalysis.class);

        assertThat(result.plausibility()).isGreaterThan(0.7);
        assertThat(result.redFlags()).isEmpty();
    }

    @Test
    void purpose_analysis_vague_사유_생활자금_감지() {
        var result = stub.call(req("생활자금"), PurposeAnalysis.class);

        assertThat(result.plausibility()).isLessThan(0.7);
        assertThat(result.redFlags()).contains(PurposeAnalysis.RedFlag.VAGUE_PURPOSE);
    }

    @Test
    void purpose_analysis_짧은_텍스트는_중립() {
        var result = stub.call(req("돈"), PurposeAnalysis.class);

        // 20자 미만은 중립 점수 (vague 아니라도)
        assertThat(result.plausibility()).isBetween(0.4, 0.7);
    }

    @Test
    void 알수없는_prompt_id는_예외() {
        var bad = new LlmRequest("unknown_prompt", 1, "s", "u", 256, 0.0);

        assertThatThrownBy(() -> stub.call(bad, PurposeAnalysis.class))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("unknown_prompt");
    }
}
