package com.bank.ai.agent.guard;

import com.bank.ai.agent.RiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SemanticDisagreementDetector 단위 테스트.
 */
class SemanticDisagreementDetectorTest {

    private final SemanticDisagreementDetector detector = new SemanticDisagreementDetector();

    // ─────────────────────────────────────────────────────────────────────
    // LOW (승인 권고) — 부정 시그널 있으면 disagreement
    // ─────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "PD가 높은 위험 수준으로 우려됩니다.",
            "DSR 초과로 부담이 큰 상황입니다.",
            "경고 항목이 확인되어 심각한 문제가 있습니다."
    })
    void LOW_부정요약이면_disagreement_true(String summary) {
        assertThat(detector.detect(RiskLevel.LOW, summary)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "PD가 안전여유 이하로 양호한 상태입니다.",
            "신용 지표 정상, 승인 적합 수준입니다.",
            "모든 지표가 안정적입니다."
    })
    void LOW_긍정요약이면_disagreement_false(String summary) {
        assertThat(detector.detect(RiskLevel.LOW, summary)).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────
    // HIGH (거절 권고) — 긍정 시그널만 있으면 disagreement
    // ─────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "모든 지표가 양호하여 승인 적합합니다.",
            "신용점수 우수, 안전 구간 이하입니다.",
            "PD가 낮아 안정적인 상환 능력이 확인됩니다."
    })
    void HIGH_긍정요약이면_disagreement_true(String summary) {
        assertThat(detector.detect(RiskLevel.HIGH, summary)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DSR 초과로 거절이 권고됩니다.",
            "PD가 임계를 초과하여 위험 수준입니다.",
            "정책 경고 항목 다수로 반려 권고."
    })
    void HIGH_부정요약이면_disagreement_false(String summary) {
        assertThat(detector.detect(RiskLevel.HIGH, summary)).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────
    // MEDIUM — 항상 false (A10 이후 정밀화 예정)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void MEDIUM은_항상_false() {
        assertThat(detector.detect(RiskLevel.MEDIUM, "위험하지만 양호한 회색지대입니다.")).isFalse();
        assertThat(detector.detect(RiskLevel.MEDIUM, "PD 초과 위험 수준입니다.")).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 엣지 케이스
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void null_요약은_false() {
        assertThat(detector.detect(RiskLevel.LOW, null)).isFalse();
        assertThat(detector.detect(RiskLevel.HIGH, null)).isFalse();
    }

    @Test
    void 빈_요약은_false() {
        assertThat(detector.detect(RiskLevel.LOW, "")).isFalse();
        assertThat(detector.detect(RiskLevel.HIGH, "   ")).isFalse();
    }

    @Test
    void 양쪽_시그널_모두_있으면_중립_판정() {
        // 위험(부정) + 안전(긍정) 동시 → disagreement=false
        String mixed = "위험 요소가 있으나 안전 임계 이하 구간입니다.";
        assertThat(detector.detect(RiskLevel.LOW, mixed)).isFalse();
        assertThat(detector.detect(RiskLevel.HIGH, mixed)).isFalse();
    }
}
