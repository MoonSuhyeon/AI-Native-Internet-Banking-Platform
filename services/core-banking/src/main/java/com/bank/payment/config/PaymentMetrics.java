package com.bank.payment.config;

import com.bank.payment.domain.mapper.OutboxMessageMapper;
import com.bank.payment.domain.mapper.PaymentInstructionMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
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
                          PaymentInstructionMapper piMapper) {
        this.registry = registry;

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

    // 지표 9: 보상 트랜잭션 발생 (type = F2_KFTC | F3_BOK | F4_KFTC | F4_BOK | F7_KFTC | F7_BOK)
    public void compensation(String type) {
        registry.counter("payment.compensation", "type", type).increment();
    }

    // 지표 10, 11: 이체 완료 + end-to-end 처리시간
    public void paymentCompleted(LocalDateTime requestedAt) {
        registry.counter("payment.instruction.completed").increment();
        if (requestedAt != null) {
            long millis = ChronoUnit.MILLIS.between(requestedAt, LocalDateTime.now());
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
}
