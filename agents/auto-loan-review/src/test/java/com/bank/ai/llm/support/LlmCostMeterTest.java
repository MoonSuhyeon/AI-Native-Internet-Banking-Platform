package com.bank.ai.llm.support;

import com.bank.ai.llm.client.LlmRequest;
import com.bank.ai.llm.config.LlmProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LlmCostMeter 단위 테스트 — plan/llm-pipeline.md §9.
 *
 * <p>Micrometer SimpleMeterRegistry 로 실제 카운터 증가 검증.
 * 일일 cap 강제, 날짜 리셋, 메트릭 태그까지 확인.
 */
class LlmCostMeterTest {

    private static final LlmProperties PROPS_CAP_100 = new LlmProperties(
            true, LlmProperties.Provider.STUB, "stub-v1", 512, 0.0, 100L, "", "", 0, 0
    );
    private static final LlmProperties PROPS_NO_CAP = new LlmProperties(
            true, LlmProperties.Provider.STUB, "stub-v1", 512, 0.0, 0L, "", "", 0, 0  // 0 = 무제한
    );

    private SimpleMeterRegistry registry;
    private LlmCostMeter meter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    private LlmCostMeter meterWith(LlmProperties props) {
        var m = new LlmCostMeter(registry, props);
        m.registerGauge();
        return m;
    }

    private static LlmRequest req(String promptId, String system, String userContent, int maxTokens) {
        return new LlmRequest(promptId, 1, system, userContent, maxTokens, 0.0);
    }

    // ── cap 강제 ──────────────────────────────────────────────

    @Test
    void cap_미설정시_무한_호출_허용() {
        meter = meterWith(PROPS_NO_CAP);
        var req = req("purpose_analysis", "system", "a".repeat(500), 512);

        // cap = 0 이면 예외 없이 통과
        assertThatCode(() -> {
            for (int i = 0; i < 10; i++) meter.checkCapOrThrow(req);
        }).doesNotThrowAnyException();
    }

    @Test
    void cap_이내면_정상_통과() {
        meter = meterWith(PROPS_CAP_100);
        // system 4자 + user 4자 = 8자 → 2 tokens — cap 100 이내
        var req = req("purpose_analysis", "sys", "user", 512);

        assertThatCode(() -> meter.checkCapOrThrow(req)).doesNotThrowAnyException();
    }

    @Test
    void cap_초과시_LlmCostExceededException_발생() {
        meter = meterWith(PROPS_CAP_100);
        // 400자 system + 400자 user = 800자 → 200 tokens > cap 100
        var bigReq = req("purpose_analysis", "s".repeat(400), "u".repeat(400), 512);

        assertThatThrownBy(() -> meter.checkCapOrThrow(bigReq))
                .isInstanceOf(LlmCostExceededException.class)
                .hasMessageContaining("cap 초과");
    }

    @Test
    void cap_초과_예외에_dailyTotal과_cap_값_포함() {
        meter = meterWith(PROPS_CAP_100);
        var bigReq = req("purpose_analysis", "s".repeat(400), "u".repeat(400), 512);

        LlmCostExceededException ex = null;
        try {
            meter.checkCapOrThrow(bigReq);
        } catch (LlmCostExceededException e) {
            ex = e;
        }
        assertThat(ex).isNotNull();
        assertThat(ex.getCap()).isEqualTo(100L);
    }

    @Test
    void cap_누적_초과시_예외() {
        meter = meterWith(PROPS_CAP_100);
        // 작은 요청을 여러 번 → 누적이 cap 초과
        var smallReq = req("purpose_analysis", "s".repeat(100), "u".repeat(100), 512);
        // 200자 → 50 tokens per call. 2회면 100, 3회면 150 > cap 100

        assertThatCode(() -> meter.checkCapOrThrow(smallReq)).doesNotThrowAnyException();
        assertThatCode(() -> meter.checkCapOrThrow(smallReq)).doesNotThrowAnyException();
        assertThatThrownBy(() -> meter.checkCapOrThrow(smallReq))
                .isInstanceOf(LlmCostExceededException.class);
    }

    // ── 카운터 ─────────────────────────────────────────────────

    @Test
    void record_success_시_토큰_카운터_증가() {
        meter = meterWith(PROPS_NO_CAP);
        var req = req("purpose_analysis", "system prompt text", "user text", 512);

        meter.record(req, "stub-v1", 10, 20, 100L, LlmCostMeter.STATUS_SUCCESS);

        double inputCount = registry.counter("llm.tokens.input",
                "model", "stub-v1", "prompt_id", "purpose_analysis").count();
        double outputCount = registry.counter("llm.tokens.output",
                "model", "stub-v1", "prompt_id", "purpose_analysis").count();

        assertThat(inputCount).isEqualTo(10.0);
        assertThat(outputCount).isEqualTo(20.0);
    }

    @Test
    void record_status별_calls_counter_증가() {
        meter = meterWith(PROPS_NO_CAP);
        var req = req("review_report_track1", "sys", "usr", 512);

        meter.record(req, "stub-v1", 5, 10, 50L, LlmCostMeter.STATUS_SUCCESS);
        meter.record(req, "stub-v1", 5, 0, 30L, LlmCostMeter.STATUS_ERROR);

        double successCount = registry.counter("llm.calls.total",
                "prompt_id", "review_report_track1", "status", "success").count();
        double errorCount = registry.counter("llm.calls.total",
                "prompt_id", "review_report_track1", "status", "error").count();

        assertThat(successCount).isEqualTo(1.0);
        assertThat(errorCount).isEqualTo(1.0);
    }

    @Test
    void checkCap_초과시_cap_exceeded_카운터_증가() {
        meter = meterWith(PROPS_CAP_100);
        var bigReq = req("purpose_analysis", "s".repeat(400), "u".repeat(400), 512);

        try { meter.checkCapOrThrow(bigReq); } catch (LlmCostExceededException ignored) {}

        double capExceededCount = registry.counter("llm.calls.total",
                "prompt_id", "purpose_analysis", "status", "cap_exceeded").count();
        assertThat(capExceededCount).isEqualTo(1.0);
    }

    @Test
    void record_inputTokens_0이면_추정값_사용() {
        meter = meterWith(PROPS_NO_CAP);
        // system 40자 + user 40자 = 80자 → 20 tokens 추정
        var req = req("purpose_analysis", "s".repeat(40), "u".repeat(40), 512);

        meter.record(req, "stub-v1", 0, 0, 100L, LlmCostMeter.STATUS_SUCCESS);

        double inputCount = registry.counter("llm.tokens.input",
                "model", "stub-v1", "prompt_id", "purpose_analysis").count();
        assertThat(inputCount).isEqualTo(20.0);  // 80 chars / 4
    }

    // ── 일일 리셋 ──────────────────────────────────────────────

    @Test
    void 날짜_변경시_dailyTotal_리셋() throws Exception {
        meter = meterWith(PROPS_CAP_100);
        var smallReq = req("p", "s".repeat(100), "u".repeat(100), 512);
        // 첫 번째 호출 — 50 tokens 소비
        meter.checkCapOrThrow(smallReq);
        assertThat(meter.getDailyTotalTokens()).isEqualTo(50L);

        // 날짜를 어제로 조작해 리셋 트리거
        forceDate(meter, java.time.LocalDate.now().minusDays(1));

        assertThat(meter.getDailyTotalTokens()).isEqualTo(0L);  // 리셋 확인
    }

    /** 리플렉션으로 currentDate 필드를 과거 날짜로 조작 — 날짜 리셋 경로 강제 실행. */
    private static void forceDate(LlmCostMeter m, java.time.LocalDate date) throws Exception {
        Field f = LlmCostMeter.class.getDeclaredField("currentDate");
        f.setAccessible(true);
        f.set(m, date);
    }
}
