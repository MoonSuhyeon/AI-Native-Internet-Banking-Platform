package com.bank.ai.shadow;

import com.bank.ai.agent.AgentOpinion;
import com.bank.ai.agent.FallbackReason;
import com.bank.ai.agent.RiskLevel;
import com.bank.ai.rule.domain.Track;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ShadowComparisonEvaluator 단위 테스트 — phase-b-operational.md §B3, phase-d-rag.md D4-2, phase-e-elasticsearch.md E4-2.
 *
 * <p>스프링 컨텍스트 없이 순수 단위 테스트.
 */
class ShadowComparisonEvaluatorTest {

    private ShadowComparisonEvaluator evaluator;

    private static final ShadowRunProperties PROPS =
            new ShadowRunProperties(true, "stub-v1", "v1", 0.10, 1.0, 45, false, 2, "inline");

    @BeforeEach
    void setUp() {
        evaluator = new ShadowComparisonEvaluator(PROPS);
    }

    // ── TC 1: 동일 의견 → diverged=false ───────────────────────────────

    @Test
    void sameOpinion_notDiverged() {
        AgentOpinion prod   = opinion(0.80, RiskLevel.LOW,    false);
        AgentOpinion shadow = opinion(0.80, RiskLevel.LOW,    false);

        ShadowComparisonResult result = evaluator.evaluate(
                1L, prod, shadow, Track.TRACK_1, "stub-v1", "v1", false, "inline");

        assertThat(result.diverged()).isFalse();
        assertThat(result.divergeReasons()).isEmpty();
    }

    // ── TC 2: riskLevel 불일치 → RISK_LEVEL_MISMATCH ───────────────────

    @Test
    void riskLevelMismatch_diverged() {
        AgentOpinion prod   = opinion(0.70, RiskLevel.LOW,    false);
        AgentOpinion shadow = opinion(0.70, RiskLevel.MEDIUM, false);

        ShadowComparisonResult result = evaluator.evaluate(
                2L, prod, shadow, Track.TRACK_3, "stub-v1", "v1", false, "inline");

        assertThat(result.diverged()).isTrue();
        assertThat(result.divergeReasons()).contains("RISK_LEVEL_MISMATCH");
    }

    // ── TC 3: decisionScore 차 > threshold → DECISION_SCORE_GAP ────────

    @Test
    void decisionScoreGap_aboveThreshold_diverged() {
        AgentOpinion prod   = opinion(0.90, RiskLevel.LOW,  false);
        AgentOpinion shadow = opinion(0.70, RiskLevel.LOW,  false);  // gap=0.20 > 0.10

        ShadowComparisonResult result = evaluator.evaluate(
                3L, prod, shadow, Track.TRACK_3, "stub-v1", "v1", false, "inline");

        assertThat(result.diverged()).isTrue();
        assertThat(result.divergeReasons()).contains("DECISION_SCORE_GAP");
    }

    @Test
    void decisionScoreGap_belowThreshold_notDiverged() {
        AgentOpinion prod   = opinion(0.80, RiskLevel.LOW, false);
        AgentOpinion shadow = opinion(0.75, RiskLevel.LOW, false);  // gap=0.05 ≤ 0.10

        ShadowComparisonResult result = evaluator.evaluate(
                4L, prod, shadow, Track.TRACK_3, "stub-v1", "v1", false, "inline");

        assertThat(result.diverged()).isFalse();
    }

    // ── TC 4: disagreement 불일치 → DISAGREEMENT_MISMATCH ───────────────

    @Test
    void disagreementMismatch_diverged() {
        AgentOpinion prod   = opinion(0.75, RiskLevel.MEDIUM, true);
        AgentOpinion shadow = opinion(0.75, RiskLevel.MEDIUM, false);

        ShadowComparisonResult result = evaluator.evaluate(
                5L, prod, shadow, Track.TRACK_3, "stub-v1", "v1", false, "inline");

        assertThat(result.diverged()).isTrue();
        assertThat(result.divergeReasons()).contains("DISAGREEMENT_MISMATCH");
    }

    // ── TC 5: fallback 의견 → 비교 생략, diverged=false ─────────────────

    @Test
    void fallbackOpinion_skipsComparison() {
        AgentOpinion prod   = AgentOpinion.fallback(FallbackReason.TOOL_ERROR);
        AgentOpinion shadow = opinion(0.80, RiskLevel.LOW, false);

        ShadowComparisonResult result = evaluator.evaluate(
                6L, prod, shadow, Track.TRACK_3, "stub-v1", "v1", false, "inline");

        assertThat(result.diverged()).isFalse();
        assertThat(result.divergeReasons()).isEmpty();
    }

    // ── TC 6: 복수 사유 동시 발생 ────────────────────────────────────────

    @Test
    void multipleReasons_allRecorded() {
        AgentOpinion prod   = opinion(0.90, RiskLevel.LOW,  true);
        AgentOpinion shadow = opinion(0.70, RiskLevel.HIGH, false);  // score gap + risk + disagreement

        ShadowComparisonResult result = evaluator.evaluate(
                7L, prod, shadow, Track.TRACK_3, "stub-v1", "v1", false, "inline");

        assertThat(result.diverged()).isTrue();
        assertThat(result.divergeReasons())
                .contains("RISK_LEVEL_MISMATCH", "DECISION_SCORE_GAP", "DISAGREEMENT_MISMATCH");
    }

    // ── TC 7: D4-2 ragEnabled=true + policyFlags 수 차이 ≥ threshold → POLICY_FLAG_DIFF ──

    @Test
    void ragEnabled_policyFlagDiff_aboveThreshold_diverged() {
        // prod: 0 flags, shadow: 3 flags → diff=3 ≥ threshold(2)
        AgentOpinion prod   = opinionWithFlags(0.75, RiskLevel.LOW, false, List.of());
        AgentOpinion shadow = opinionWithFlags(0.75, RiskLevel.LOW, false,
                List.of("DSR_THRESHOLD_WARNING", "LTV_THRESHOLD_WARNING", "CRED_SCORE_WARNING"));

        ShadowComparisonResult result = evaluator.evaluate(
                8L, prod, shadow, Track.TRACK_3, "stub-v2", "v2", true, "es");

        assertThat(result.diverged()).isTrue();
        assertThat(result.divergeReasons()).contains("POLICY_FLAG_DIFF");
        assertThat(result.ragEnabled()).isTrue();
        assertThat(result.ragBackend()).isEqualTo("es");
    }

    @Test
    void ragEnabled_policyFlagDiff_belowThreshold_notDiverged() {
        // prod: 1 flag, shadow: 2 flags → diff=1 < threshold(2)
        AgentOpinion prod   = opinionWithFlags(0.75, RiskLevel.LOW, false,
                List.of("DSR_THRESHOLD_WARNING"));
        AgentOpinion shadow = opinionWithFlags(0.75, RiskLevel.LOW, false,
                List.of("DSR_THRESHOLD_WARNING", "LTV_THRESHOLD_WARNING"));

        ShadowComparisonResult result = evaluator.evaluate(
                9L, prod, shadow, Track.TRACK_3, "stub-v2", "v2", true, "es");

        assertThat(result.diverged()).isFalse();
    }

    @Test
    void ragDisabled_policyFlagDiff_isIgnored() {
        // ragEnabled=false: policyFlags 차이는 체크 안 함
        AgentOpinion prod   = opinionWithFlags(0.75, RiskLevel.LOW, false, List.of());
        AgentOpinion shadow = opinionWithFlags(0.75, RiskLevel.LOW, false,
                List.of("DSR_THRESHOLD_WARNING", "LTV_THRESHOLD_WARNING", "CRED_SCORE_WARNING"));

        ShadowComparisonResult result = evaluator.evaluate(
                10L, prod, shadow, Track.TRACK_3, "stub-v1", "v1", false, "inline");

        assertThat(result.diverged()).isFalse();
        assertThat(result.ragEnabled()).isFalse();
        assertThat(result.ragBackend()).isEqualTo("inline");
    }

    // ── TC 8: E4-2 ragBackend 값이 결과에 전달됨 ─────────────────────────

    @Test
    void ragBackend_isPreservedInResult() {
        AgentOpinion prod   = opinion(0.80, RiskLevel.LOW, false);
        AgentOpinion shadow = opinion(0.80, RiskLevel.LOW, false);

        ShadowComparisonResult inlineResult = evaluator.evaluate(
                11L, prod, shadow, Track.TRACK_1, "stub-v1", "v1", false, "inline");
        ShadowComparisonResult esResult = evaluator.evaluate(
                12L, prod, shadow, Track.TRACK_1, "stub-v2", "v2", true, "es");

        assertThat(inlineResult.ragBackend()).isEqualTo("inline");
        assertThat(esResult.ragBackend()).isEqualTo("es");
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    private static AgentOpinion opinion(double decisionScore, RiskLevel riskLevel, boolean disagreement) {
        return AgentOpinion.of(decisionScore, 0.1, riskLevel, List.of(), "테스트 요약", List.of(), disagreement);
    }

    private static AgentOpinion opinionWithFlags(double decisionScore, RiskLevel riskLevel,
                                                  boolean disagreement, List<String> flags) {
        return AgentOpinion.of(decisionScore, 0.1, riskLevel, flags, "테스트 요약", List.of(), disagreement);
    }
}
