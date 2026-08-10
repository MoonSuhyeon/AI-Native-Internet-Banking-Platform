package com.bank.payment.domain.service;

import com.bank.payment.domain.service.TransferLimitPolicy.Decision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이체한도 판정.
 *
 * <p><b>왜 이렇게 촘촘히 보는가.</b> 금액 비교는 경계에서 틀리기 쉬운데, 틀려도
 * 대부분의 거래는 정상으로 흐른다. 한도에 정확히 걸치는 고객만 잘못 처리되므로
 * 조용히 남는다 — 통과시켜야 할 사람을 막으면 민원이 오지만, 막아야 할 사람을
 * 통과시키면 아무도 모른다.
 *
 * <p>고친 결함도 여기에 있다. 예전 구현은 당일 누적을 세지 않고 이번 건 금액만
 * 계좌 한도와 비교했다. 그래서 <b>"1일 한도" 가 실제로는 "1회 한도" 로 동작했고</b>,
 * 한도의 90% 짜리를 하루에 몇 번이고 보낼 수 있었다.
 */
class TransferLimitPolicyTest {

    private static final long MAN = 10_000L;          // 1만원
    private static final long DAILY = 100 * MAN;      // 100만원
    private static final long ONCE = 50 * MAN;        // 50만원

    // ── 1회 한도 ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("1회 한도")
    class OnceLimit {

        @Test
        @DisplayName("한도보다 크면 막는다")
        void overOnceIsDenied() {
            Decision d = TransferLimitPolicy.evaluate(ONCE + 1, 0, DAILY, ONCE, null);

            assertThat(d.allowed()).isFalse();
            assertThat(d.reason()).contains("1회");
        }

        @Test
        @DisplayName("한도와 같으면 통과한다 — 초과가 아니라 '같음' 은 허용이다")
        void exactlyOnceIsAllowed() {
            // 여기서 부등호를 잘못 쓰면 100만원 한도인 고객이 100만원을 못 보낸다.
            // 화면 안내와 어긋나고, 그 사람만 겪는 일이라 신고되기 전엔 모른다.
            assertThat(TransferLimitPolicy.evaluate(ONCE, 0, DAILY, ONCE, null).allowed())
                    .isTrue();
        }

        @Test
        @DisplayName("1회 한도가 없으면 건너뛴다")
        void nullOnceIsSkipped() {
            assertThat(TransferLimitPolicy.evaluate(DAILY, 0, DAILY, null, null).allowed())
                    .isTrue();
        }
    }

    // ── 1일 누적 ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("1일 누적 한도")
    class DailyLimit {

        @Test
        @DisplayName("이번 건을 더해서 넘으면 막는다 — 예전에는 이 누적이 없었다")
        void cumulativeIsCounted() {
            // 오늘 이미 90만원을 보냈고 20만원을 더 보내려 한다.
            Decision d = TransferLimitPolicy.evaluate(20 * MAN, 90 * MAN, DAILY, ONCE, null);

            assertThat(d.allowed()).isFalse();
            assertThat(d.reason()).contains("1일").contains("900000");
        }

        @Test
        @DisplayName("더해도 한도 이내면 통과한다")
        void withinCumulativeIsAllowed() {
            assertThat(TransferLimitPolicy.evaluate(10 * MAN, 90 * MAN, DAILY, ONCE, null)
                    .allowed()).isTrue();
        }

        @Test
        @DisplayName("더한 값이 한도와 정확히 같으면 통과한다")
        void exactlyDailyIsAllowed() {
            assertThat(TransferLimitPolicy.evaluate(10 * MAN, 90 * MAN, DAILY, null, null)
                    .allowed()).isTrue();
        }

        @Test
        @DisplayName("이미 한도를 채웠으면 1원도 더 못 보낸다")
        void exhaustedBlocksEverything() {
            assertThat(TransferLimitPolicy.evaluate(1, DAILY, DAILY, null, null).allowed())
                    .isFalse();
        }

        @Test
        @DisplayName("'이미 넘었는가' 가 아니라 '보내고 나면 넘는가' 로 본다")
        void asksAboutTheStateAfterThisTransfer() {
            // 누적은 한도 이내지만 이번 건을 더하면 넘는 경우.
            // 이번 건을 빼고 비교하면 여기서 통과해 한도를 넘긴 채로 끝난다.
            assertThat(TransferLimitPolicy.evaluate(30 * MAN, 80 * MAN, DAILY, null, null)
                    .allowed()).isFalse();
        }
    }

    // ── 낮은 쪽 적용 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("고객 한도와 계좌 한도 중 낮은 쪽")
    class LowerOf {

        @Test
        @DisplayName("계좌 한도가 낮으면 계좌 한도로 막는다")
        void accountLimitWins() {
            // 고객 한도 100만, 계좌 한도 30만. 50만을 보내려 한다.
            Decision d = TransferLimitPolicy.evaluate(50 * MAN, 0, DAILY, null, 30 * MAN);

            assertThat(d.allowed()).isFalse();
            assertThat(d.reason()).contains("300000");
        }

        @Test
        @DisplayName("고객 한도가 낮으면 고객 한도로 막는다")
        void customerLimitWins() {
            Decision d = TransferLimitPolicy.evaluate(50 * MAN, 0, 30 * MAN, null, DAILY);

            assertThat(d.allowed()).isFalse();
            assertThat(d.reason()).contains("300000");
        }

        @Test
        @DisplayName("한쪽만 설정돼 있으면 그쪽을 쓴다")
        void onlyOneSideSet() {
            assertThat(TransferLimitPolicy.lowerOf(null, 30 * MAN)).isEqualTo(30 * MAN);
            assertThat(TransferLimitPolicy.lowerOf(30 * MAN, null)).isEqualTo(30 * MAN);
            assertThat(TransferLimitPolicy.lowerOf(null, null)).isNull();
        }

        @Test
        @DisplayName("둘 다 없으면 1일 한도를 보지 않는다")
        void noDailyLimitAtAll() {
            assertThat(TransferLimitPolicy.evaluate(999_999_999L, 0, null, null, null)
                    .allowed()).isTrue();
        }
    }

    // ── 설정값 방어 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("한도 0 은 '설정 없음' 으로 본다 — 실수 하나로 전 고객이 막히면 안 된다")
    void zeroLimitIsTreatedAsUnset() {
        // 0 을 "아무것도 못 보냄" 으로 읽으면, 마이그레이션이나 기본값 실수 한 번에
        // 모든 고객의 이체가 멈춘다. 그런 실패는 크고 즉시 드러나지만 피해도 크다.
        assertThat(TransferLimitPolicy.evaluate(10 * MAN, 0, 0L, 0L, 0L).allowed()).isTrue();
    }

    @Test
    @DisplayName("음수 한도도 설정 없음으로 본다")
    void negativeLimitIsTreatedAsUnset() {
        assertThat(TransferLimitPolicy.evaluate(10 * MAN, 0, -1L, -1L, -1L).allowed()).isTrue();
    }

    @Test
    @DisplayName("사유에 판단 근거가 남는다 — 없으면 고객 문의에 답할 수 없다")
    void reasonExplainsTheNumbers() {
        Decision d = TransferLimitPolicy.evaluate(20 * MAN, 90 * MAN, DAILY, ONCE, null);

        assertThat(d.reason())
                .contains("200000")     // 요청
                .contains("900000")     // 오늘 누적
                .contains("1100000")    // 합계
                .contains("1000000");   // 한도
    }
}
