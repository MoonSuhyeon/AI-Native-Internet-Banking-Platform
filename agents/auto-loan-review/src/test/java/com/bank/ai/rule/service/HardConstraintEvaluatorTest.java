package com.bank.ai.rule.service;

import com.bank.ai.rule.TestRequests;
import com.bank.ai.rule.config.RuleEngineProperties;
import com.bank.ai.rule.config.RuleEngineProperties.HardConstraints;
import com.bank.ai.rule.domain.HardFailReason;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HardConstraintEvaluatorTest {

    private static final RuleEngineProperties PROPS = new RuleEngineProperties(
            new HardConstraints(0.40, 0.70, 600, 0, 19),
            0.30, 0.50, Map.of(),
            0.95, 0.20,
            true, false
    );

    private final HardConstraintEvaluator evaluator = new HardConstraintEvaluator(PROPS);

    @Test
    void 정상_입력이면_위반_없음() {
        var result = evaluator.evaluate(TestRequests.healthy());
        assertThat(result).isEmpty();
    }

    @Test
    void DSR_한도_초과시_DSR_EXCEEDED() {
        var req = TestRequests.baseline(0.41, 0.50, 750, 0, 35, "MORT_001", "regular");
        assertThat(evaluator.evaluate(req)).containsExactly(HardFailReason.DSR_EXCEEDED);
    }

    @Test
    void LTV_한도_초과시_LTV_EXCEEDED() {
        var req = TestRequests.baseline(0.30, 0.71, 750, 0, 35, "MORT_001", "regular");
        assertThat(evaluator.evaluate(req)).containsExactly(HardFailReason.LTV_EXCEEDED);
    }

    @Test
    void 신용점수_최저_미달시_CREDIT_SCORE_BELOW_MIN() {
        var req = TestRequests.baseline(0.30, 0.50, 599, 0, 35, "MORT_001", "regular");
        assertThat(evaluator.evaluate(req)).containsExactly(HardFailReason.CREDIT_SCORE_BELOW_MIN);
    }

    @Test
    void 진행중_연체_있으면_DELINQUENCY_PRESENT() {
        var req = TestRequests.baseline(0.30, 0.50, 750, 1, 35, "MORT_001", "regular");
        assertThat(evaluator.evaluate(req)).containsExactly(HardFailReason.DELINQUENCY_24M_PRESENT);
    }

    @Test
    void 미성년이면_AGE_BELOW_MIN() {
        var req = TestRequests.baseline(0.30, 0.50, 750, 0, 18, "MORT_001", "regular");
        assertThat(evaluator.evaluate(req)).containsExactly(HardFailReason.AGE_BELOW_MIN);
    }

    @Test
    void 한도_경계값은_통과한다() {
        // 한도값과 정확히 같으면 위반 아님 (< vs ≤ 의미 검증)
        var req = TestRequests.baseline(0.40, 0.70, 600, 0, 19, "MORT_001", "regular");
        assertThat(evaluator.evaluate(req)).isEmpty();
    }

    @Test
    void 다중_위반은_모두_반환된다() {
        var req = TestRequests.baseline(0.45, 0.80, 500, 2, 17, "MORT_001", "regular");
        assertThat(evaluator.evaluate(req)).containsExactlyInAnyOrder(
                HardFailReason.DSR_EXCEEDED,
                HardFailReason.LTV_EXCEEDED,
                HardFailReason.CREDIT_SCORE_BELOW_MIN,
                HardFailReason.DELINQUENCY_24M_PRESENT,
                HardFailReason.AGE_BELOW_MIN
        );
    }

    @Test
    void null_필드는_위반_판정_안함() {
        // 모든 검증 대상 필드가 null — missing 처리는 ML 모델 분기에 위임
        var req = TestRequests.baseline(null, null, null, null, null, "MORT_001", "regular");
        assertThat(evaluator.evaluate(req)).isEmpty();
    }
}
