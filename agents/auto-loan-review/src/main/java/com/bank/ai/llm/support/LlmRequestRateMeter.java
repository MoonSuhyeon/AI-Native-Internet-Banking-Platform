package com.bank.ai.llm.support;

import com.bank.ai.llm.config.LlmProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM 호출 RPD/RPM 비율 제한 미터링 — pre-review-agent-plan.md 운영 대비책 §1.
 *
 * <p>토큰 캡(입출력 byte) 을 다루는 {@link LlmCostMeter} 와 달리 본 클래스는
 * <strong>요청 건수</strong> 만 추적한다. Gemini AI Studio 무료 한도:
 * <ul>
 *   <li>RPD 1500 — 일간 요청 수</li>
 *   <li>RPM 15  — 분간 요청 수 (60s 고정 윈도우)</li>
 * </ul>
 * 두 한도 중 하나라도 초과하면 {@link #tryAcquire()} 가 {@code false} 를 반환.
 * 호출 측은 즉시 {@code TemplateFallback(fallback_reason=LLM_RATE_LIMITED)} 로 우회해야 함.
 *
 * <h2>제공 메트릭 (게이지 2종)</h2>
 * <ul>
 *   <li>{@code ai.agent.rpd.remaining} — 오늘 남은 RPD 슬롯</li>
 *   <li>{@code ai.agent.rpm.remaining} — 현재 분 남은 RPM 슬롯</li>
 * </ul>
 *
 * <h2>스레드 안전성</h2>
 * RPM/RPD 카운터는 {@link AtomicInteger}. 윈도우 리셋은 double-checked locking.
 * 윈도우 경계에서 일시적으로 cap+1 요청이 통과할 수 있으나 비율 제한 특성상 허용.
 */
@Slf4j
@Component
public class LlmRequestRateMeter {

    private final LlmProperties llmProps;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /** 현재 RPM 윈도우(60s) 내 요청 수. */
    private final AtomicInteger rpmCount = new AtomicInteger(0);
    /** 현재 RPM 윈도우 시작 시각 (epoch millis). */
    private volatile long rpmWindowStartMs;

    /** 오늘 누적 요청 수. */
    private final AtomicInteger rpdCount = new AtomicInteger(0);
    /** 마지막 RPD 리셋 날짜. */
    private volatile LocalDate currentDate;

    @Autowired
    public LlmRequestRateMeter(LlmProperties llmProps, MeterRegistry meterRegistry) {
        this(llmProps, meterRegistry, Clock.systemUTC());
    }

    LlmRequestRateMeter(LlmProperties llmProps, MeterRegistry meterRegistry, Clock clock) {
        this.llmProps = llmProps;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.rpmWindowStartMs = clock.millis();
        this.currentDate = LocalDate.now(clock);
    }

    @PostConstruct
    public void registerGauges() {
        meterRegistry.gauge("ai.agent.rpd.remaining", this, m -> m.getRpdRemaining());
        meterRegistry.gauge("ai.agent.rpm.remaining", this, m -> m.getRpmRemaining());
        log.info("LlmRequestRateMeter 초기화 — rpdCap={} rpmCap={}",
                llmProps.dailyRequestCap(), llmProps.rpmCap());
    }

    // ─────────────────────────────────────────────────────────────

    /**
     * LLM 호출 전 한도 체크 + 선점.
     *
     * @return true 면 호출 허용, false 면 rate-limited (fallback 처리 필요)
     */
    public boolean tryAcquire() {
        resetIfNeeded();

        int rpdCap = llmProps.dailyRequestCap();
        int rpmCap = llmProps.rpmCap();

        if (rpdCap > 0 && rpdCount.get() >= rpdCap) {
            log.warn("LlmRequestRateMeter: RPD 초과 — count={} cap={}", rpdCount.get(), rpdCap);
            return false;
        }
        if (rpmCap > 0 && rpmCount.get() >= rpmCap) {
            log.warn("LlmRequestRateMeter: RPM 초과 — count={} cap={}", rpmCount.get(), rpmCap);
            return false;
        }

        rpdCount.incrementAndGet();
        rpmCount.incrementAndGet();
        return true;
    }

    /** 오늘 남은 RPD 슬롯 (cap=0 이면 Integer.MAX_VALUE). */
    public int getRpdRemaining() {
        int cap = llmProps.dailyRequestCap();
        return cap <= 0 ? Integer.MAX_VALUE : Math.max(0, cap - rpdCount.get());
    }

    /** 현재 분 남은 RPM 슬롯 (cap=0 이면 Integer.MAX_VALUE). */
    public int getRpmRemaining() {
        int cap = llmProps.rpmCap();
        return cap <= 0 ? Integer.MAX_VALUE : Math.max(0, cap - rpmCount.get());
    }

    /** 테스트용 — 현재 RPD 누적 수. */
    int getRpdCount() { return rpdCount.get(); }

    /** 테스트용 — 현재 RPM 누적 수. */
    int getRpmCount() { return rpmCount.get(); }

    // ─────────────────────────────────────────────────────────────

    private void resetIfNeeded() {
        long nowMs = clock.millis();
        if (nowMs - rpmWindowStartMs >= 60_000L) {
            synchronized (this) {
                if (nowMs - rpmWindowStartMs >= 60_000L) {
                    log.debug("LlmRequestRateMeter: RPM 윈도우 리셋 (count={}→0)", rpmCount.get());
                    rpmCount.set(0);
                    rpmWindowStartMs = nowMs;
                }
            }
        }

        LocalDate today = LocalDate.now(clock);
        if (!today.equals(currentDate)) {
            synchronized (this) {
                if (!today.equals(currentDate)) {
                    log.info("LlmRequestRateMeter: RPD 리셋 ({} → {})", currentDate, today);
                    rpdCount.set(0);
                    currentDate = today;
                }
            }
        }
    }
}
