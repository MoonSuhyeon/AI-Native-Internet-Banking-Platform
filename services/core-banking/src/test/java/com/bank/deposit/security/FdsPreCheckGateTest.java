package com.bank.deposit.security;

import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 이체 승인 전 이상거래 점검.
 *
 * <p><b>여기서 지키는 것은 장애 시 동작이다.</b> 탐지기가 죽었을 때 전부 막으면 FDS
 * 장애가 곧 결제 장애가 되고, 전부 통과시키면 FDS 를 둔 의미가 없다. 그래서 금액
 * 구간으로 가른다 — 이 분기가 조용히 사라지면 어느 쪽이든 사고가 되는데,
 * 코드는 멀쩡히 돌고 화면도 정상이라 테스트로만 잡을 수 있다.
 *
 * <p>축소 판정(degraded)을 장애와 같이 다루는 것도 여기서 고정한다. 응답이 200 이라고
 * "이상 없음" 으로 읽으면 고액이 점검 없이 나간다.
 */
class FdsPreCheckGateTest {

    private static final String SENDER = "9001";
    private static final String FROM = "110-222-333333";
    private static final String TO = "999-888-777777";

    private static final long SMALL = 50_000L;
    private static final long HIGH = 6_000_000L;

    private MockRestServiceServer server;
    private FdsPreCheckGate gate;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        AmountTierPolicy tierPolicy = new AmountTierPolicy();
        ReflectionTestUtils.setField(tierPolicy, "normalFrom", 100_000L);
        ReflectionTestUtils.setField(tierPolicy, "highFrom", 5_000_000L);
        ReflectionTestUtils.setField(tierPolicy, "veryHighFrom", 50_000_000L);

        gate = new FdsPreCheckGate(builder.baseUrl("http://fds").build(), tierPolicy,
                new SimpleMeterRegistry());
        ReflectionTestUtils.setField(gate, "delayMinutes", 30L);
        ReflectionTestUtils.setField(gate, "enabled", true);
    }

    private void respond(String json) {
        server.expect(requestTo("http://fds/api/v1/internal/fds/precheck"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private PreCheckDecision evaluate(long amount, boolean hasToken) {
        return gate.evaluate(SENDER, FROM, "004", TO, amount, false, "MOBILE", hasToken);
    }

    @Nested
    @DisplayName("판정을 받았을 때")
    class WhenAnswered {

        @Test
        @DisplayName("PASS 는 통과시킨다")
        void passProceeds() {
            respond("""
                    {"tier":"PASS","signals":[],"degraded":false}""");

            assertThatCode(() -> evaluate(SMALL, false)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("MONITOR 는 표시만 하는 등급이라 거래를 막지 않는다")
        void monitorProceeds() {
            respond("""
                    {"tier":"MONITOR","signals":[],"degraded":false}""");

            assertThatCode(() -> evaluate(SMALL, false)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("STEP_UP 인데 인증이 없으면 막는다")
        void stepUpWithoutTokenIsRejected() {
            respond("""
                    {"tier":"STEP_UP","signals":[],"degraded":false}""");

            assertThatThrownBy(() -> evaluate(SMALL, false))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.TRANSFER_APPROVAL_REQUIRED));
        }

        @Test
        @DisplayName("STEP_UP 이어도 이미 인증했으면 통과시킨다")
        void stepUpWithTokenProceeds() {
            respond("""
                    {"tier":"STEP_UP","signals":[],"degraded":false}""");

            // 이미 본인 확인을 마친 고객을 다시 막으면 인증을 두 번 요구하는 셈이다.
            assertThatCode(() -> evaluate(SMALL, true)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("DELAY 는 막지 않는다 — 접수하고 실행을 미룬다")
        void delayDoesNotRejectTheTransfer() {
            respond("""
                    {"tier":"DELAY","signals":["HIGH_AMOUNT"],"degraded":false}""");

            PreCheckDecision decision = evaluate(SMALL, true);

            // 예외로 끊으면 호출부가 실패로 다루고 고객에게도 실패로 보인다.
            // 실제로는 취소할 기회를 준 것인데 그 의미가 사라진다.
            assertThat(decision.isDelayed()).isTrue();
            assertThat(decision.delay()).isEqualTo(Duration.ofMinutes(30));
            assertThat(decision.reasons()).contains("HIGH_AMOUNT");
        }

        @Test
        @DisplayName("DELAY 는 인증 여부와 무관하다 — 인증했다고 지연이 풀리지 않는다")
        void delayIsNotWaivedByAuthentication() {
            respond("""
                    {"tier":"DELAY","signals":[],"degraded":false}""");

            // 지연은 본인 확인으로 해소되는 종류가 아니다. 본인이 속아서 보내는
            // 경우(보이스피싱)가 바로 이 장치가 노리는 상황이다.
            assertThat(evaluate(SMALL, false).isDelayed()).isTrue();
        }

        @Test
        @DisplayName("BLOCK 은 인증이 있어도 막는다")
        void blockRejectsEvenWithToken() {
            respond("""
                    {"tier":"BLOCK","signals":[],"degraded":false}""");

            assertThatThrownBy(() -> evaluate(SMALL, true))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.TRANSFER_BLOCKED_BY_RISK));
        }
    }

    @Nested
    @DisplayName("판정을 못 받았을 때 — 금액 구간으로 갈린다")
    class WhenUnavailable {

        @Test
        @DisplayName("소액은 통과시킨다 — 탐지기 장애가 결제 장애가 되면 안 된다")
        void smallProceedsOnOutage() {
            server.expect(requestTo("http://fds/api/v1/internal/fds/precheck"))
                    .andRespond(withServerError());

            assertThatCode(() -> evaluate(SMALL, false)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("고액은 진행하지 않는다 — 점검 없이 나가면 FDS 가 꺼진 것과 같다")
        void highIsHeldOnOutage() {
            server.expect(requestTo("http://fds/api/v1/internal/fds/precheck"))
                    .andRespond(withServerError());

            assertThatThrownBy(() -> evaluate(HIGH, true))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.TRANSFER_RISK_CHECK_UNAVAILABLE));
        }

        @Test
        @DisplayName("축소 판정은 장애와 같이 다룬다 — 200 이라고 이상 없음이 아니다")
        void degradedIsTreatedAsOutage() {
            // 신호가 비어 있는 모습은 정상 거래와 똑같다. degraded 를 무시하면
            // 고액이 점검 없이 나가는데 로그에도 아무 흔적이 없다.
            respond("""
                    {"tier":"PASS","signals":[],"degraded":true}""");

            assertThatThrownBy(() -> evaluate(HIGH, true))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.TRANSFER_RISK_CHECK_UNAVAILABLE));
        }

        @Test
        @DisplayName("응답이 비어도 장애로 본다")
        void emptyBodyIsTreatedAsOutage() {
            server.expect(requestTo("http://fds/api/v1/internal/fds/precheck"))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> evaluate(HIGH, true))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    @DisplayName("꺼져 있으면 호출하지 않는다 — 단계적 도입용")
    void disabledSkipsCall() {
        ReflectionTestUtils.setField(gate, "enabled", false);

        // server.expect 를 걸지 않았으므로, 호출하면 검증에서 걸린다.
        assertThatCode(() -> evaluate(HIGH, false)).doesNotThrowAnyException();
        server.verify();
    }
}
