package com.bank.fds.detect;

import com.bank.fds.detect.DetectionSignal.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대응 등급 판정.
 *
 * <p>여기서 지키는 것은 <b>수위가 뒤집히지 않는다</b>는 것이다. 등급은 조용히 틀린다 —
 * 약하게 판정해도 예외가 안 나고, 화면은 멀쩡하며, 다만 잡아야 할 거래가 통과할 뿐이다.
 * 그래서 규제 필수 신호와 사후 강등 두 지점을 못박는다.
 */
class ResponseTierPolicyTest {

    private final ResponseTierPolicy policy = new ResponseTierPolicy();

    private DetectionSignal signal(Severity severity) {
        return DetectionSignal.of("TEST_" + severity, severity, "테스트 신호");
    }

    @Test
    @DisplayName("신호가 없으면 통과")
    void noSignalPasses() {
        assertThat(policy.decide(List.of(), true)).isEqualTo(ResponseTier.PASS);
        assertThat(policy.decide(null, true)).isEqualTo(ResponseTier.PASS);
    }

    @Test
    @DisplayName("약한 신호 하나로는 고객을 붙잡지 않는다")
    void singleLowOnlyMonitors() {
        // 약한 신호로 큐를 채우면 심사원이 지치고 결국 전부 형식적으로 처리된다 —
        // FDS 가 실패하는 전형적인 방식이다.
        assertThat(policy.decide(List.of(signal(Severity.LOW)), true))
                .isEqualTo(ResponseTier.MONITOR);
        assertThat(policy.decide(List.of(signal(Severity.LOW), signal(Severity.LOW)), true))
                .isEqualTo(ResponseTier.MONITOR);
    }

    @Nested
    @DisplayName("규제 필수 신호")
    class Mandatory {

        @Test
        @DisplayName("다른 신호가 하나도 없어도 사람에게 간다")
        void mandatoryAloneGoesToHuman() {
            ResponseTier tier = policy.decide(List.of(signal(Severity.MANDATORY)), true);

            assertThat(tier).isEqualTo(ResponseTier.HOLD_REVIEW);
            assertThat(tier.requiresHumanReview()).isTrue();
        }

        @Test
        @DisplayName("약한 신호와 함께 와도 약한 쪽으로 끌려가지 않는다")
        void mandatoryIsNotDilutedByWeakSignals() {
            // 점수를 합산하는 구조였다면 LOW 가 평균을 끌어내려 통과했을 수 있다.
            // 요주의인물 적중 같은 항목은 "모델이 낮게 봤으니 통과" 가 성립하지 않는다.
            ResponseTier tier = policy.decide(
                    List.of(signal(Severity.LOW), signal(Severity.MANDATORY), signal(Severity.LOW)),
                    true);

            assertThat(tier).isEqualTo(ResponseTier.HOLD_REVIEW);
        }
    }

    @Nested
    @DisplayName("강도에 따라 수위가 올라간다")
    class Ladder {

        @Test
        @DisplayName("중간 하나면 추가인증, 둘이면 지연")
        void mediumEscalates() {
            assertThat(policy.decide(List.of(signal(Severity.MEDIUM)), true))
                    .isEqualTo(ResponseTier.STEP_UP);
            assertThat(policy.decide(List.of(signal(Severity.MEDIUM), signal(Severity.MEDIUM)), true))
                    .isEqualTo(ResponseTier.DELAY);
        }

        @Test
        @DisplayName("강한 신호가 겹치면 사전에서는 막는다")
        void twoHighBlocksInline() {
            assertThat(policy.decide(List.of(signal(Severity.HIGH), signal(Severity.HIGH)), true))
                    .isEqualTo(ResponseTier.BLOCK);
        }

        @Test
        @DisplayName("강한 신호 하나는 받쳐 주는 신호가 있어야 사람에게 간다")
        void singleHighNeedsSupport() {
            assertThat(policy.decide(List.of(signal(Severity.HIGH)), true))
                    .isEqualTo(ResponseTier.DELAY);
            assertThat(policy.decide(List.of(signal(Severity.HIGH), signal(Severity.MEDIUM)), true))
                    .isEqualTo(ResponseTier.HOLD_REVIEW);
        }
    }

    @Nested
    @DisplayName("사후 판정")
    class PostHoc {

        @Test
        @DisplayName("끝난 거래는 막을 수 없으므로 지급정지 권고로 올린다")
        void blockBecomesFreezeRecommendation() {
            ResponseTier tier = policy.decide(
                    List.of(signal(Severity.HIGH), signal(Severity.HIGH)), false);

            // 조용히 통과시키면 안 된다. 사전이었다면 막았을 거래이므로
            // 사후에 더 약하게 대응하는 것은 잘못이다.
            assertThat(tier).isEqualTo(ResponseTier.FREEZE_RECOMMEND);
            assertThat(tier.requiresHumanReview()).isTrue();
        }

        @Test
        @DisplayName("막을 필요 없는 등급은 그대로 둔다")
        void weakerTiersAreUnchanged() {
            assertThat(policy.decide(List.of(signal(Severity.MEDIUM)), false))
                    .isEqualTo(ResponseTier.STEP_UP);
            assertThat(policy.decide(List.of(signal(Severity.MANDATORY)), false))
                    .isEqualTo(ResponseTier.HOLD_REVIEW);
        }
    }
}
