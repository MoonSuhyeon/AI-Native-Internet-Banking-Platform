package com.bank.fds.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 탐지 지표.
 *
 * <p>레포의 다른 에이전트와 같은 축을 쓴다 — 최종 성능은 결국
 * {@code fraud_recommendation_reviewed_total}(사람이 권고를 뒤집는 비율)로 드러난다.
 * 여기서는 그 앞단, 즉 <b>무엇이 왜 사건이 됐는가</b>를 센다.
 */
@Component
public class FdsMetrics {

    private final MeterRegistry registry;

    public FdsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 소비한 이벤트. outcome = processed | duplicate | unenriched */
    public void eventConsumed(String outcome) {
        Counter.builder("fds_event_consumed_total")
                .description("소비한 결제 이벤트 수")
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    /** 룰 히트. */
    public void ruleHit(String rule) {
        Counter.builder("fds_detection_total")
                .description("룰 히트 수")
                .tag("rule", rule)
                .register(registry)
                .increment();
    }

    /**
     * 사건화. trigger = rule | anomaly | both
     *
     * <p>히트와 따로 세는 이유: 한 거래에 룰이 여러 개 걸려도 사건은 하나다.
     * 두 값을 같이 봐야 "룰이 과하게 겹치는지"가 보인다.
     */
    public void caseCreated(String trigger) {
        Counter.builder("fds_case_created_total")
                .description("조사로 넘긴 사건 수")
                .tag("trigger", trigger)
                .register(registry)
                .increment();
    }

    /**
     * 룰과 이상탐지가 엇갈린 건.
     *
     * <p>direction = rule_only(룰만 걸림 — 오탐 후보) | anomaly_only(ML만 걸림 — 신종 수법 후보)
     *
     * <p><b>왜 세는가.</b> 임계값을 언제 조정해야 하는지가 이 값으로 나온다.
     * 안 세면 감으로 정할 수밖에 없다. auto-loan-review 가
     * {@code SemanticDisagreementDetector} 로 하는 것과 같은 발상이다.
     */
    public void ruleMlDisagreement(String direction) {
        Counter.builder("fds_rule_ml_disagreement_total")
                .description("룰과 이상탐지 판정이 엇갈린 수")
                .tag("direction", direction)
                .register(registry)
                .increment();
    }

    /** 보강 실패 — 룰이 조용히 못 잡게 되는 경로라 반드시 눈에 보여야 한다. */
    public void enrichFailed() {
        Counter.builder("fds_enrich_failed_total")
                .description("거래 상세 보강 실패 수")
                .register(registry)
                .increment();
    }
}
