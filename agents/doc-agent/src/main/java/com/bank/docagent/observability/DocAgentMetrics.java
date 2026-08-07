package com.bank.docagent.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * 서류 심사 에이전트 성능 지표.
 *
 * <p><b>무엇을 재는가.</b> 이 에이전트의 성능은 처리량이 아니라 <b>자동 판정을 사람이
 * 뒤집는 비율</b>로 드러난다. 파이프라인이 HOLD 를 내면 심사원이 CLEARED(정상) 또는
 * CONFIRMED_FORGERY(위조 확정)로 결정하는데, 그 결과가 곧 채택률이다.
 *
 * <p>레포의 다른 에이전트도 같은 축을 쓴다 — auto-loan-review 의
 * {@code ai_agent_disagreement_total}, fraud-agent 의
 * {@code fraud_recommendation_reviewed_total}. 이름은 서비스별 접두사를 붙이는 집 스타일을
 * 따른다.
 *
 * <p><b>왜 자동 판정 분포를 함께 두는가.</b> 채택률만 보면 "HOLD 를 남발해서 사람 일이
 * 늘었다"와 "판정이 정확해졌다"가 구분되지 않는다. AUTO_PASS·HOLD·NEEDS_RESUBMIT 의
 * 비율이 그 맥락을 준다.
 *
 * <p><b>단계별 지연을 나누는 이유.</b> 파이프라인이 OCR·분류·LLM추출·위조분석·검증 다섯
 * 단계이고 앞 셋은 외부 사이드카 호출이다. 합쳐 재면 느려졌을 때 어느 사이드카가
 * 문제인지 알 수 없다.
 *
 * <p>라벨에 제출 ID·심사원 ID 는 넣지 않는다 — 카디널리티가 터지고, 개인 단위 추적은
 * 지표가 아니라 감사로그가 할 일이다.
 */
@Component
public class DocAgentMetrics {

    private final MeterRegistry registry;

    public DocAgentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 파이프라인이 낸 자동 판정. status = AUTO_PASS | HOLD | NEEDS_RESUBMIT | ... */
    public void recordVerdict(String status, String docType) {
        Counter.builder("doc_agent_verdict_total")
                .description("파이프라인 자동 판정 수")
                .tag("status", status)
                .tag("doc_type", docType)
                .register(registry)
                .increment();
    }

    /**
     * 심사원이 HOLD 건을 결정한 결과.
     *
     * <p>decision = CLEARED(자동 판정을 뒤집어 정상 처리) | CONFIRMED_FORGERY(자동 판정 확인).
     * 이 서비스의 핵심 지표다 — 채택률 = CONFIRMED_FORGERY / 전체.
     */
    public void recordHumanDecision(String decision) {
        Counter.builder("doc_agent_human_review_total")
                .description("심사원이 결정한 HOLD 건 수 (HITL)")
                .tag("decision", decision)
                .register(registry)
                .increment();
    }

    /** 파이프라인 전체 소요 시간. */
    public Timer.Sample startPipeline() {
        return Timer.start(registry);
    }

    public void stopPipeline(Timer.Sample sample, String status) {
        sample.stop(Timer.builder("doc_agent_pipeline_duration_seconds")
                .description("서류 처리 파이프라인 소요 시간")
                .tag("status", status)
                .register(registry));
    }

    /** 사이드카 단계별 소요 시간. stage = ocr | forgery | extract */
    public Timer.Sample startStage() {
        return Timer.start(registry);
    }

    public void stopStage(Timer.Sample sample, String stage, boolean ok) {
        sample.stop(Timer.builder("doc_agent_stage_duration_seconds")
                .description("파이프라인 단계별 소요 시간 (사이드카 호출 포함)")
                .tag("stage", stage)
                .tag("outcome", ok ? "ok" : "error")
                .register(registry));
    }

    /**
     * 파이프라인이 예외로 끝난 수.
     *
     * <p>실패는 판정을 만들지 못하므로 verdict 에 잡히지 않는다. 따로 세지 않으면
     * "서류가 안 들어왔다"와 "처리가 터졌다"가 구별되지 않는다.
     */
    public void recordPipelineFailure(String stage) {
        Counter.builder("doc_agent_pipeline_failed_total")
                .description("파이프라인이 예외로 끝난 수")
                .tag("stage", stage)
                .register(registry)
                .increment();
    }
}
