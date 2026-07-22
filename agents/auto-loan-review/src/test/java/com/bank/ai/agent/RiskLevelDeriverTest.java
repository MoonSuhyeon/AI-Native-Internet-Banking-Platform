package com.bank.ai.agent;

import com.bank.ai.rule.domain.Track;
import com.bank.ai.rule.domain.TrackDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RiskLevelDeriver 단위 테스트.
 */
class RiskLevelDeriverTest {

    @Test
    void Track1은_항상_LOW() {
        var decision = new TrackDecision(Track.TRACK_1, List.of(), 0.05, 0.95, 0.347, 0.104, "안전");
        assertThat(RiskLevelDeriver.derive(decision)).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void Track2는_항상_HIGH() {
        var decision = new TrackDecision(Track.TRACK_2, List.of(), 0.80, 0.10, 0.347, 0.104, "거절");
        assertThat(RiskLevelDeriver.derive(decision)).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void Track3_decisionScore_충분하면_MEDIUM() {
        var decision = new TrackDecision(Track.TRACK_3, List.of(), 0.35, 0.65, 0.347, 0.104, "회색지대");
        assertThat(RiskLevelDeriver.derive(decision)).isEqualTo(RiskLevel.MEDIUM);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.20, 0.39, 0.399})
    void Track3_decisionScore_임계미만_HIGH_보정(double decisionScore) {
        var decision = new TrackDecision(Track.TRACK_3, List.of(), 0.55, decisionScore, 0.347, 0.104, "회색지대");
        assertThat(RiskLevelDeriver.derive(decision)).isEqualTo(RiskLevel.HIGH);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.40, 0.50, 0.80, 1.0})
    void Track3_decisionScore_임계이상_MEDIUM(double decisionScore) {
        var decision = new TrackDecision(Track.TRACK_3, List.of(), 0.35, decisionScore, 0.347, 0.104, "회색지대");
        assertThat(RiskLevelDeriver.derive(decision)).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void Track3_decisionScore_null이면_MEDIUM() {
        var decision = new TrackDecision(Track.TRACK_3, List.of(), 0.35, null, 0.347, 0.104, "회색지대");
        assertThat(RiskLevelDeriver.derive(decision)).isEqualTo(RiskLevel.MEDIUM);
    }
}
