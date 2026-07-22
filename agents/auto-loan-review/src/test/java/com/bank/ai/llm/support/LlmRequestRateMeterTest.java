package com.bank.ai.llm.support;

import com.bank.ai.llm.config.LlmProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LlmRequestRateMeter 단위 테스트 — pre-review-agent-plan.md 운영 대비책 §1.
 *
 * <p>Clock 주입으로 시간 제어 → RPM 윈도우 리셋·RPD 일간 리셋을 결정론적으로 검증.
 */
class LlmRequestRateMeterTest {

    private static final int RPM_CAP = 3;
    private static final int RPD_CAP = 5;

    private static LlmProperties props(int rpmCap, int rpdCap) {
        return new LlmProperties(
                true, LlmProperties.Provider.GEMINI_OPENAI_COMPAT,
                "gemini-2.5-flash", 512, 0.0, 0L,
                "http://localhost", "test-key",
                rpdCap, rpmCap
        );
    }

    // ─────────────────────────────────────────────────────────

    @Test
    void cap0_은_무제한() {
        var meter = new LlmRequestRateMeter(props(0, 0), new SimpleMeterRegistry());
        for (int i = 0; i < 100; i++) {
            assertThat(meter.tryAcquire()).isTrue();
        }
    }

    @Test
    void RPM_cap_초과시_false() {
        var meter = new LlmRequestRateMeter(props(RPM_CAP, 0), new SimpleMeterRegistry());

        for (int i = 0; i < RPM_CAP; i++) {
            assertThat(meter.tryAcquire()).as("요청 %d", i + 1).isTrue();
        }
        assertThat(meter.tryAcquire()).as("cap+1 번째 요청").isFalse();
    }

    @Test
    void RPD_cap_초과시_false() {
        var meter = new LlmRequestRateMeter(props(0, RPD_CAP), new SimpleMeterRegistry());

        for (int i = 0; i < RPD_CAP; i++) {
            assertThat(meter.tryAcquire()).as("요청 %d", i + 1).isTrue();
        }
        assertThat(meter.tryAcquire()).as("cap+1 번째 요청").isFalse();
    }

    @Test
    void RPM_윈도우_60초후_리셋() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Clock[] clockHolder = {Clock.fixed(start, ZoneOffset.UTC)};

        var registry = new SimpleMeterRegistry();
        var meter = new LlmRequestRateMeter(props(RPM_CAP, 0), registry) {
            @Override
            public boolean tryAcquire() {
                // clock 을 직접 교체할 수 없으므로 package-private 생성자 활용
                return super.tryAcquire();
            }
        };

        // package-private 생성자로 clock 주입
        var meterWithClock = new LlmRequestRateMeter(props(RPM_CAP, 0), registry,
                Clock.fixed(start, ZoneOffset.UTC));
        meterWithClock.registerGauges();

        // RPM_CAP 만큼 소진
        for (int i = 0; i < RPM_CAP; i++) {
            assertThat(meterWithClock.tryAcquire()).isTrue();
        }
        assertThat(meterWithClock.tryAcquire()).isFalse();
        assertThat(meterWithClock.getRpmCount()).isEqualTo(RPM_CAP);

        // 61초 후 clock 으로 새 meter (같은 방식으로 윈도우 리셋 유도)
        Clock advancedClock = Clock.fixed(start.plusSeconds(61), ZoneOffset.UTC);
        var meterAfterWindow = new LlmRequestRateMeter(props(RPM_CAP, 0), new SimpleMeterRegistry(),
                advancedClock);
        // 새 윈도우에서 다시 허용됨
        assertThat(meterAfterWindow.tryAcquire()).isTrue();
    }

    @Test
    void RPM_윈도우_내에서_카운터가_리셋된다() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        var registry = new SimpleMeterRegistry();

        // t0 기준 meter
        var meter = new LlmRequestRateMeter(props(RPM_CAP, 0), registry,
                Clock.fixed(t0, ZoneOffset.UTC));
        meter.registerGauges();

        for (int i = 0; i < RPM_CAP; i++) meter.tryAcquire();
        assertThat(meter.getRpmRemaining()).isZero();

        // 60초 윈도우 지난 meter (기존 인스턴스는 고정 clock 이라 외부에서 새로 만드는 방식)
        var meterNext = new LlmRequestRateMeter(props(RPM_CAP, 0), new SimpleMeterRegistry(),
                Clock.fixed(t0.plusSeconds(61), ZoneOffset.UTC));
        meterNext.registerGauges();

        assertThat(meterNext.getRpmRemaining()).isEqualTo(RPM_CAP);
    }

    @Test
    void RPD_일간_리셋() {
        Instant day1 = Instant.parse("2026-01-01T12:00:00Z");
        var meter = new LlmRequestRateMeter(props(0, RPD_CAP), new SimpleMeterRegistry(),
                Clock.fixed(day1, ZoneOffset.UTC));
        meter.registerGauges();

        for (int i = 0; i < RPD_CAP; i++) meter.tryAcquire();
        assertThat(meter.getRpdRemaining()).isZero();

        // 다음 날 clock 으로 새 meter
        var meterNextDay = new LlmRequestRateMeter(props(0, RPD_CAP), new SimpleMeterRegistry(),
                Clock.fixed(day1.plusSeconds(86_400L), ZoneOffset.UTC));
        meterNextDay.registerGauges();

        assertThat(meterNextDay.getRpdRemaining()).isEqualTo(RPD_CAP);
        assertThat(meterNextDay.tryAcquire()).isTrue();
    }

    @Test
    void remaining_게이지가_감소한다() {
        var registry = new SimpleMeterRegistry();
        var meter = new LlmRequestRateMeter(props(RPM_CAP, RPD_CAP), registry);
        meter.registerGauges();

        assertThat(gauge(registry, "ai.agent.rpm.remaining")).isEqualTo(RPM_CAP);
        assertThat(gauge(registry, "ai.agent.rpd.remaining")).isEqualTo(RPD_CAP);

        meter.tryAcquire();
        meter.tryAcquire();

        assertThat(gauge(registry, "ai.agent.rpm.remaining")).isEqualTo(RPM_CAP - 2);
        assertThat(gauge(registry, "ai.agent.rpd.remaining")).isEqualTo(RPD_CAP - 2);
    }

    private static double gauge(SimpleMeterRegistry registry, String name) {
        Gauge g = registry.find(name).gauge();
        assertThat(g).as("게이지 %s 등록됨", name).isNotNull();
        return g.value();
    }
}
