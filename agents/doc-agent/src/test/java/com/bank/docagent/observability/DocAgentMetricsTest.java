package com.bank.docagent.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서류 심사 지표가 실제로 기록되는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> 지표는 틀려도 아무도 에러를 내지 않는다. 계측을 빠뜨리거나
 * 라벨을 잘못 달아도 앱은 정상 동작하고 대시보드만 조용히 빈다. 이 레포에서 실제로
 * 그런 일이 있었다 — auto-loan-review 는 지표를 다 만들어 놓고 compose 에서 포트를
 * 안 열어 두 달 넘게 하나도 수집되지 않았다.
 *
 * <p>여기서는 "값이 올라가는가"와 "라벨이 의도한 축인가"를 못박는다. 특히 채택률
 * ({@code doc_agent_human_review_total})은 이 에이전트 성능의 핵심 축이라
 * 두 결정(CLEARED / CONFIRMED_FORGERY)을 모두 확인한다.
 */
class DocAgentMetricsTest {

    private MeterRegistry registry;
    private DocAgentMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new DocAgentMetrics(registry);
    }

    @Test
    @DisplayName("자동 판정이 상태·서류유형별로 세어진다")
    void verdictCountedByStatusAndDocType() {
        metrics.recordVerdict("AUTO_PASS", "INCOME_CERT");
        metrics.recordVerdict("AUTO_PASS", "INCOME_CERT");
        metrics.recordVerdict("HOLD", "REGISTRY_DEED");

        assertThat(counter("doc_agent_verdict_total", "status", "AUTO_PASS", "doc_type", "INCOME_CERT"))
                .isEqualTo(2.0);
        assertThat(counter("doc_agent_verdict_total", "status", "HOLD", "doc_type", "REGISTRY_DEED"))
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("심사원 결정이 양쪽 모두 기록된다 — 채택률을 계산할 수 있어야 한다")
    void humanDecisionRecordsBothOutcomes() {
        // 한쪽만 세면 "아무도 안 봤다"와 "사람이 뒤집었다"가 구별되지 않는다.
        metrics.recordHumanDecision("CONFIRMED_FORGERY");
        metrics.recordHumanDecision("CLEARED");
        metrics.recordHumanDecision("CLEARED");

        assertThat(counter("doc_agent_human_review_total", "decision", "CONFIRMED_FORGERY")).isEqualTo(1.0);
        assertThat(counter("doc_agent_human_review_total", "decision", "CLEARED")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("단계별 지연이 단계·성패로 나뉘어 기록된다")
    void stageTimerSplitsByStageAndOutcome() {
        Timer.Sample ok = metrics.startStage();
        metrics.stopStage(ok, "ocr", true);
        Timer.Sample bad = metrics.startStage();
        metrics.stopStage(bad, "extract", false);

        assertThat(timerCount("doc_agent_stage_duration_seconds", "stage", "ocr", "outcome", "ok"))
                .isEqualTo(1L);
        assertThat(timerCount("doc_agent_stage_duration_seconds", "stage", "extract", "outcome", "error"))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("파이프라인 실패가 단계와 함께 세어진다")
    void pipelineFailureCountedWithStage() {
        // 실패는 판정을 만들지 못해 verdict 에 안 잡힌다 — 따로 세야 보인다.
        metrics.recordPipelineFailure("ocr");

        assertThat(counter("doc_agent_pipeline_failed_total", "stage", "ocr")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("파이프라인 전체 시간이 판정 상태별로 기록된다")
    void pipelineTimerTaggedByStatus() {
        Timer.Sample sample = metrics.startPipeline();
        metrics.stopPipeline(sample, "HOLD");

        assertThat(timerCount("doc_agent_pipeline_duration_seconds", "status", "HOLD")).isEqualTo(1L);
    }

    private double counter(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    private long timerCount(String name, String... tags) {
        return registry.get(name).tags(tags).timer().count();
    }
}
