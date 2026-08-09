package com.bank.payment.config;

import com.bank.payment.domain.mapper.OutboxMessageMapper;
import com.bank.payment.domain.mapper.PaymentInstructionMapper;
import com.bank.payment.domain.mapper.ReconciliationMapper;
import com.bank.payment.domain.reconciliation.ReconciliationBreakType;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

/**
 * payment-service 커스텀 메트릭 중앙 관리.
 * Gauge(DB 조회 기반)는 생성자에서 등록, Counter/Timer는 메서드로 제공.
 */
@Component
public class PaymentMetrics {

    private final MeterRegistry registry;
    private final Timer durationTimer;

    public PaymentMetrics(MeterRegistry registry,
                          OutboxMessageMapper outboxMapper,
                          PaymentInstructionMapper piMapper,
                          ReconciliationMapper reconciliationMapper) {
        this.registry = registry;

        // 대사 미해결 불일치 — 유형별 Gauge.
        //
        // 유형마다 따로 등록하는 이유: 합계만 보면 "3건" 이 사소한 미결 3건인지
        // 돈이 실제로 어긋난 3건인지 구별되지 않는다. REVERSED_BUT_SETTLED 가
        // 1건이라도 뜨면 즉시 봐야 하고, STALE_PENDING 이 10건인 것과는 무게가 다르다.
        //
        // 0 을 명시적으로 내보내는 것이 중요하다. 시리즈가 아예 없으면 그래프에
        // 아무것도 안 그려지는데, 그것이 "깨끗하다" 인지 "대사가 안 돈다" 인지
        // 구별되지 않는다.
        for (ReconciliationBreakType type : ReconciliationBreakType.values()) {
            Gauge.builder("payment.reconciliation.break.open",
                          reconciliationMapper,
                          m -> (double) m.countOpenByType().stream()
                                  .filter(c -> type.name().equals(c.breakType()))
                                  .mapToLong(c -> c.count())
                                  .sum())
                 .tag("type", type.name())
                 .description("미해결 대사 불일치 건수 — 내부 원장과 외부 청산이 어긋난 지점")
                 .register(registry);
        }

        // 지표 4: Outbox PENDING 적체 수 (Gauge — Prometheus 스크레이프 시 DB 조회)
        Gauge.builder("payment.outbox.pending", outboxMapper, m -> (double) m.countPending())
             .description("Outbox PENDING 메시지 수")
             .register(registry);

        // 지표 13: 미완료 거래 수 (Gauge)
        Gauge.builder("payment.instruction.incomplete", piMapper, m -> (double) m.countIncomplete())
             .description("미완료 거래 수 (COMPLETED/FAILED/CANCELED 제외)")
             .register(registry);

        // 지표 11: end-to-end 처리시간 히스토그램 (p50/p95/p99 — Prometheus histogram_quantile 용)
        this.durationTimer = Timer.builder("payment.instruction.duration")
                .publishPercentileHistogram(true)
                .register(registry);
    }

    // 지표 5: Outbox 발행 성공
    public void outboxPublished() {
        registry.counter("payment.outbox.publish", "result", "success").increment();
    }

    // 지표 5: Outbox 발행 실패
    public void outboxFailed() {
        registry.counter("payment.outbox.publish", "result", "failure").increment();
    }

    // 지표 6: DLQ 유입 (cluster = kftc | bok)
    public void dlq(String cluster) {
        registry.counter("payment.kafka.dlq", "cluster", cluster).increment();
    }

    // 지표 8: 메시지 소비 처리 완료 (topic = kftc.network.request 등)
    public void consumed(String topic) {
        registry.counter("payment.kafka.consume", "topic", topic).increment();
    }

    /**
     * 이상거래 점검이 지연시킨 건이 실행 전에 취소됐다.
     *
     * <p>게이트가 세는 {@code payment.fds.precheck.outcome{outcome="delayed"}} 와 짝이다.
     * 두 값의 비율이 지연 장치가 실제로 사고를 막고 있는지를 보여준다.
     * 지연 건수만 보면 "많이 지연시켰다" 는 것 외에는 아무것도 알 수 없다.
     */
    public void delayedTransferCancelled() {
        registry.counter("payment.fds.delayed.cancelled.total").increment();
    }

    // 지표 9: 보상 트랜잭션 발생 (type = F2_KFTC | F3_BOK | F4_KFTC | F4_BOK | F7_KFTC | F7_BOK)
    public void compensation(String type) {
        registry.counter("payment.compensation", "type", type).increment();
    }

    // 지표 10, 11: 이체 완료 + end-to-end 처리시간
    public void paymentCompleted(OffsetDateTime requestedAt) {
        registry.counter("payment.instruction.completed").increment();
        if (requestedAt != null) {
            long millis = ChronoUnit.MILLIS.between(requestedAt, OffsetDateTime.now());
            durationTimer.record(millis, TimeUnit.MILLISECONDS);
        }
    }

    // 지표 10: 이체 실패
    public void paymentFailed() {
        registry.counter("payment.instruction.failed").increment();
    }

    // KFTC 마감배치 정산 실패 (건별 격리 후 계속된 진짜 실패)
    public void kftcSettlementFailed() {
        registry.counter("payment.kftc.settlement.failed").increment();
    }

    // 지표 12: 중복 거래 감지 (멱등키 충돌)
    public void idempotencyDuplicate() {
        registry.counter("payment.idempotency.duplicate").increment();
    }

    /**
     * 대사 1회 실행.
     *
     * <p>불일치 건수만 보면 <b>대사가 멈춘 것과 깨끗한 것이 똑같이 0 으로 보인다.</b>
     * 실행 자체를 세야 "며칠째 안 돌고 있다" 를 알 수 있다.
     */
    public void reconciliationRun(String network, int breakCount) {
        registry.counter("payment.reconciliation.run", "network", network).increment();
        registry.counter("payment.reconciliation.break.found", "network", network)
                .increment(breakCount);
    }
}
