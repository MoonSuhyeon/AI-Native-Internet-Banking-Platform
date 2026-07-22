package com.bank.ai.rule.service;

import com.bank.ai.rule.TestRequests;
import com.bank.ai.rule.config.RuleEngineProperties;
import com.bank.ai.rule.config.RuleEngineProperties.HardConstraints;
import com.bank.ai.rule.domain.HardFailReason;
import com.bank.ai.rule.domain.Track;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrackClassifierTest {

    private static final RuleEngineProperties PROPS = new RuleEngineProperties(
            new HardConstraints(0.40, 0.70, 600, 0, 19),
            0.30,
            0.50,
            Map.of("MORT_001", Map.of("regular", 0.40)),
            0.95, 0.20,
            true, false
    );
    // τ=0.40, safetyTau = 0.40 × 0.30 = 0.12

    private final TrackClassifier classifier = new TrackClassifier(
            new HardConstraintEvaluator(PROPS),
            new PolicyMatrix(PROPS)
    );

    @Test
    void hard_fail_있으면_PD_무관하게_Track2() {
        // DSR 위반 + PD 매우 낮음 (정상 같지만 hard fail 우선)
        var req = TestRequests.baseline(0.50, 0.50, 750, 0, 35, "MORT_001", "regular");
        var decision = classifier.classify(req, 0.01);
        assertThat(decision.track()).isEqualTo(Track.TRACK_2);
        assertThat(decision.hardFails()).contains(HardFailReason.DSR_EXCEEDED);
        assertThat(decision.rationale()).contains("Hard fail");
    }

    @Test
    void PD가_안전여유_이하면_Track1() {
        var req = TestRequests.healthy();
        var decision = classifier.classify(req, 0.10);  // ≤ 0.12
        assertThat(decision.track()).isEqualTo(Track.TRACK_1);
        assertThat(decision.hardFails()).isEmpty();
        assertThat(decision.rationale()).contains("자동 승인");
    }

    @Test
    void PD가_안전여유_초과_매트릭스_이하면_Track3() {
        var req = TestRequests.healthy();
        var decision = classifier.classify(req, 0.25);  // > 0.12, ≤ 0.40
        assertThat(decision.track()).isEqualTo(Track.TRACK_3);
        assertThat(decision.rationale()).contains("사람 심사");
    }

    @Test
    void PD가_매트릭스_초과면_Track2() {
        var req = TestRequests.healthy();
        var decision = classifier.classify(req, 0.60);  // > 0.40
        assertThat(decision.track()).isEqualTo(Track.TRACK_2);
        assertThat(decision.hardFails()).isEmpty();
        assertThat(decision.rationale()).contains("PD 초과");
    }

    @Test
    void 분기_경계값_PD_safetyTau와_같으면_Track1() {
        var req = TestRequests.healthy();
        var decision = classifier.classify(req, 0.12);  // == safetyTau
        assertThat(decision.track()).isEqualTo(Track.TRACK_1);
    }

    @Test
    void 분기_경계값_PD_매트릭스와_같으면_Track3() {
        var req = TestRequests.healthy();
        var decision = classifier.classify(req, 0.40);  // == threshold
        assertThat(decision.track()).isEqualTo(Track.TRACK_3);
    }

    @Test
    void TrackDecision은_사용된_임계치도_보존한다() {
        var req = TestRequests.healthy();
        var decision = classifier.classify(req, 0.25);
        assertThat(decision.pd()).isEqualTo(0.25);
        assertThat(decision.pdThreshold()).isEqualTo(0.40);
        assertThat(decision.safetyMarginThreshold()).isEqualTo(0.12);
    }
}
