package com.bank.fds.detect;

import com.bank.fds.api.dto.PreCheckRequest;
import com.bank.fds.api.dto.PreCheckResponse;
import com.bank.fds.observability.FdsMetrics;
import com.bank.fds.guidance.GuidanceComposer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 사전 점검.
 *
 * <p><b>가장 중요한 것은 축소 판정을 숨기지 않는 것이다.</b> Redis 가 죽어 프로파일을
 * 못 읽으면 신호가 안 잡히고, 결과는 "이상 없음" 과 똑같이 생긴다. 그대로 통과시키면
 * 탐지가 꺼져 있는 줄도 모르고 며칠이 지난다 — 이 레포에서 이미 겪은 종류의 실패다.
 * 그래서 {@code degraded} 로 구분해 결제계가 금액 구간별 정책을 적용하게 한다.
 */
class PreCheckServiceTest {

    private RiskStateStore riskStateStore;
    private PreCheckService service;

    @BeforeEach
    void setUp() {
        riskStateStore = mock(RiskStateStore.class);
        service = new PreCheckService(
                riskStateStore, new ResponseTierPolicy(), new FdsMetrics(new SimpleMeterRegistry()),
                new GuidanceComposer());
        ReflectionTestUtils.setField(service, "highAmountThreshold", 10_000_000L);
        ReflectionTestUtils.setField(service, "velocityThreshold", 5L);

        // 기본: 정상 상태, 위험 표시 없음, 속도 낮음
        when(riskStateStore.isAvailable()).thenReturn(true);
        when(riskStateStore.elevatedTier(anyString())).thenReturn(Optional.empty());
        when(riskStateStore.touchVelocity(anyString())).thenReturn(Optional.of(1L));
    }

    private PreCheckRequest request(long amount) {
        return new PreCheckRequest("9001", "110-222-333333", "004", "999-888-777777",
                amount, false, "MOBILE");
    }

    @Test
    @DisplayName("평범한 거래는 통과한다")
    void ordinaryTransferPasses() {
        PreCheckResponse res = service.evaluate(request(50_000L));

        assertThat(res.tier()).isEqualTo(ResponseTier.PASS);
        assertThat(res.signals()).isEmpty();
        assertThat(res.degraded()).isFalse();
    }

    @Test
    @DisplayName("사후 탐지가 올려 둔 고객은 사전에서 걸린다 — 두 계층이 이어져야 한다")
    void elevatedCustomerIsCaughtInline() {
        when(riskStateStore.elevatedTier("9001"))
                .thenReturn(Optional.of(ResponseTier.FREEZE_RECOMMEND));

        PreCheckResponse res = service.evaluate(request(50_000L));

        // 사후가 남긴 표시를 사전이 읽지 못하면, 무거운 분석 결과가 다음 거래를
        // 막는 데 전혀 쓰이지 않는다. 두 계층을 나눈 의미가 사라진다.
        assertThat(res.signals()).extracting(DetectionSignal::code).contains("ELEVATED_RISK_STATE");
        assertThat(res.tier()).isNotEqualTo(ResponseTier.PASS);
    }

    @Test
    @DisplayName("위험 표시와 고액이 겹치면 사람이 본다")
    void elevatedPlusHighAmountEscalates() {
        when(riskStateStore.elevatedTier("9001"))
                .thenReturn(Optional.of(ResponseTier.HOLD_REVIEW));

        PreCheckResponse res = service.evaluate(request(15_000_000L));

        assertThat(res.tier().requiresHumanReview()).isTrue();
    }

    @Test
    @DisplayName("속도가 임계를 넘으면 신호가 붙는다")
    void velocityAboveThresholdSignals() {
        when(riskStateStore.touchVelocity("9001")).thenReturn(Optional.of(9L));

        PreCheckResponse res = service.evaluate(request(50_000L));

        assertThat(res.signals()).extracting(DetectionSignal::code).contains("VELOCITY");
        assertThat(res.tier()).isEqualTo(ResponseTier.STEP_UP);
    }

    @Test
    @DisplayName("조회가 안 되면 축소 판정이라고 알린다 — 이상 없음과 구별돼야 한다")
    void degradedIsReportedNotHidden() {
        when(riskStateStore.isAvailable()).thenReturn(false);
        when(riskStateStore.elevatedTier(anyString())).thenReturn(Optional.empty());
        when(riskStateStore.touchVelocity(anyString())).thenReturn(Optional.empty());

        PreCheckResponse res = service.evaluate(request(50_000L));

        // 신호가 없다는 점은 정상 거래와 똑같이 생겼다. 여기서 degraded 를 빠뜨리면
        // 결제계는 "안전하다" 고 읽는다.
        assertThat(res.signals()).isEmpty();
        assertThat(res.tier()).isEqualTo(ResponseTier.PASS);
        assertThat(res.degraded())
                .as("판정을 못 한 것과 이상이 없는 것은 다른 사건이다")
                .isTrue();
    }
}
