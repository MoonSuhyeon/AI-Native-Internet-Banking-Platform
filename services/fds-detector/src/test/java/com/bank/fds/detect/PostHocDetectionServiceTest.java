package com.bank.fds.detect;

import com.bank.fds.dispatch.InvestigationDispatcher;
import com.bank.fds.enrich.PaymentDetail;
import com.bank.fds.observability.FdsMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 완료된 거래에 대한 재판정.
 *
 * <p><b>여기서 가장 중요한 것은 폐루프가 닫히는지다.</b> 사후 분석 결과를
 * {@link RiskStateStore#elevate} 로 남기지 않으면, 사전 점검은 언제나 빈 상태를 읽고
 * 무거운 분석이 아무 데도 쓰이지 않는다. 그런데 그렇게 되어도 예외는 나지 않고
 * 탐지 지표도 정상으로 보인다 — 테스트로만 잡을 수 있는 종류다.
 *
 * <p>심야 판정을 KST 로 하는 것도 고정한다. UTC 로 재면 9시간이 밀려
 * 한국의 아침이 심야로 잡힌다.
 */
class PostHocDetectionServiceTest {

    private RiskStateStore riskStateStore;
    private InvestigationDispatcher dispatcher;
    private PostHocDetectionService service;

    @BeforeEach
    void setUp() {
        riskStateStore = mock(RiskStateStore.class);
        dispatcher = mock(InvestigationDispatcher.class);
        service = new PostHocDetectionService(
                riskStateStore, new ResponseTierPolicy(), dispatcher,
                new FdsMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(service, "highAmountThreshold", 10_000_000L);
        ReflectionTestUtils.setField(service, "nightFromHour", 0);
        ReflectionTestUtils.setField(service, "nightToHour", 6);

        when(riskStateStore.isFirstTimeReceiver(anyString(), anyString())).thenReturn(false);
        // 기본은 누적 임계 미달
        when(riskStateStore.addDailyAmount(anyString(), any())).thenReturn(Optional.of(100_000L));
        ReflectionTestUtils.setField(service, "dailyCumulativeThreshold", 20_000_000L);
    }

    private PaymentDetail detail(long amount, String completedAt, boolean intraBank) {
        return new PaymentDetail("PI-1", "TXN-1", "COMPLETED", "9001", "110-222-333333",
                "004", "999-888-777777", "홍길동", amount, intraBank, "KFTC", "MOBILE",
                OffsetDateTime.parse(completedAt), OffsetDateTime.parse(completedAt), "20260808");
    }

    @Test
    @DisplayName("평범한 거래는 사건이 되지 않는다")
    void ordinaryTransferCreatesNoCase() {
        // KST 오후 3시, 소액, 자행, 아는 수취인
        var outcome = service.detect(detail(50_000L, "2026-08-08T06:00:00Z", true));

        assertThat(outcome.tier()).isEqualTo(ResponseTier.PASS);
        verify(riskStateStore, never()).elevate(anyString(), any());
    }

    @Test
    @DisplayName("조치가 필요하면 고객을 표시해 둔다 — 이게 없으면 사전 점검이 늘 빈 상태를 읽는다")
    void actionableTierElevatesRiskState() {
        // 타행 고액 + 신규 수취인 + 심야 → 사전 점검이 알아야 할 고객이다.
        when(riskStateStore.isFirstTimeReceiver(eq("9001"), anyString())).thenReturn(true);

        var outcome = service.detect(detail(20_000_000L, "2026-08-07T20:00:00Z", false));

        assertThat(outcome.tier()).isNotIn(ResponseTier.PASS, ResponseTier.MONITOR);
        // 사람 검토가 필요한 등급에만 남기면 STEP_UP·DELAY 가 전달되지 않는다.
        // 정작 "이 고객을 한동안 조심하자" 에 해당하는 등급들이다.
        verify(riskStateStore).elevate("9001", outcome.tier());
    }

    @Test
    @DisplayName("심야는 KST 로 판정한다 — UTC 로 재면 한국의 아침이 심야가 된다")
    void nightTimeUsesKst() {
        // 2026-08-07T20:00:00Z = KST 2026-08-08 05:00 → 심야
        var night = service.detect(detail(50_000L, "2026-08-07T20:00:00Z", true));
        assertThat(night.signals()).extracting(DetectionSignal::code).contains("NIGHT_TIME");

        // 2026-08-08T00:00:00Z = KST 09:00 → 심야 아님
        var morning = service.detect(detail(50_000L, "2026-08-08T00:00:00Z", true));
        assertThat(morning.signals()).extracting(DetectionSignal::code).doesNotContain("NIGHT_TIME");
    }

    @Test
    @DisplayName("타행 고액은 자행 고액보다 강하게 본다 — 되돌리기가 훨씬 어렵다")
    void crossBankHighAmountIsStronger() {
        var crossBank = service.detect(detail(20_000_000L, "2026-08-08T00:00:00Z", false));
        var intraBank = service.detect(detail(20_000_000L, "2026-08-08T00:00:00Z", true));

        assertThat(crossBank.signals()).extracting(DetectionSignal::code)
                .contains("CROSS_BANK_HIGH_AMOUNT");
        assertThat(intraBank.signals()).extracting(DetectionSignal::code)
                .doesNotContain("CROSS_BANK_HIGH_AMOUNT");
    }

    @Test
    @DisplayName("사건이 되면 조사에 넘긴다 — 넘기지 않으면 큐가 비어 있게 된다")
    void caseIsDispatchedToInvestigation() {
        when(riskStateStore.isFirstTimeReceiver(eq("9001"), anyString())).thenReturn(true);

        var outcome = service.detect(detail(20_000_000L, "2026-08-07T20:00:00Z", false));

        // if 로 감싸면 사건화가 아예 안 일어나게 되어도 조용히 통과한다.
        // 실제로 그 상태였다 — 사후 경로에 HIGH 신호가 없어 사람 검토 등급에
        // 도달할 수 없었고, 조사 큐는 영원히 비어 있었을 것이다.
        assertThat(outcome.tier().requiresHumanReview())
                .as("타행 고액 + 신규 수취인 + 심야면 사람이 봐야 한다")
                .isTrue();
        verify(dispatcher).dispatch(any(), any(), anyDouble());
    }

    @Test
    @DisplayName("1일 누적이 검토 기준을 넘으면 다른 신호가 없어도 사람에게 간다")
    void dailyCumulativeThresholdIsMandatory() {
        // 규제가 요구하는 항목이라 점수와 무관하다. 이 신호를 만드는 곳이 없으면
        // 정책의 MANDATORY 분기는 영원히 실행되지 않는 죽은 코드가 된다.
        when(riskStateStore.addDailyAmount(anyString(), any()))
                .thenReturn(Optional.of(25_000_000L));

        // 소액·자행·아는 수취인 — 누적 말고는 아무 신호도 없다.
        var outcome = service.detect(detail(50_000L, "2026-08-08T06:00:00Z", true));

        assertThat(outcome.signals()).extracting(DetectionSignal::code)
                .contains("DAILY_CUMULATIVE_THRESHOLD");
        assertThat(outcome.tier().requiresHumanReview()).isTrue();
    }

    @Test
    @DisplayName("약한 신호만 모이면 사람을 부르지 않는다")
    void weakSignalsAloneDoNotEscalate() {
        // 심야 + 신규 수취인 (둘 다 LOW) — 흔한 조합이다.
        when(riskStateStore.isFirstTimeReceiver(anyString(), anyString())).thenReturn(true);

        var outcome = service.detect(detail(50_000L, "2026-08-07T20:00:00Z", true));

        // 이걸로 큐를 채우면 심사원이 지치고 결국 전부 형식적으로 처리된다.
        assertThat(outcome.tier()).isEqualTo(ResponseTier.MONITOR);
        verify(riskStateStore, never()).elevate(anyString(), any());
    }
}
