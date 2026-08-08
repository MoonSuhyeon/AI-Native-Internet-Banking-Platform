package com.bank.fds.consumer;

import com.bank.fds.detect.PostHocDetectionService;
import com.bank.fds.enrich.PaymentDetail;
import com.bank.fds.enrich.PaymentDetailClient;
import com.bank.fds.observability.FdsMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 결제 이벤트 소비 경로.
 *
 * <p><b>여기서 지키는 것은 두 가지다.</b>
 *
 * <p>1) <b>ack 는 어떤 경로로 끝나든 반드시 한 번 한다.</b> ack 를 빠뜨리면 같은 이벤트에
 * 막혀 그 뒤 거래를 통째로 놓친다. 예외가 나도 마찬가지다 — 한 건을 못 잡는 것보다
 * 스트림이 서는 쪽이 훨씬 나쁘다.
 *
 * <p>2) <b>중복은 처리하지 않는다.</b> Kafka 는 at-least-once 라 재처리가 정상 상황인데,
 * 속도·분할·fan-out 룰은 건수를 세는 룰이라 중복이 들어오면 없던 이상거래가 생긴다.
 * 예외도 로그도 없는 오탐이라 테스트로만 잡을 수 있다.
 */
class PaymentCompletedConsumerTest {

    private PaymentDetailClient detailClient;
    private ProcessedEventGuard guard;
    private MeterRegistry registry;
    private PaymentCompletedConsumer consumer;
    private Acknowledgment ack;

    @BeforeEach
    void setUp() {
        detailClient = mock(PaymentDetailClient.class);
        guard = mock(ProcessedEventGuard.class);
        registry = new SimpleMeterRegistry();
        ack = mock(Acknowledgment.class);
        consumer = new PaymentCompletedConsumer(
                new ObjectMapper(), detailClient, guard,
                mock(PostHocDetectionService.class), new FdsMetrics(registry));

        // 기본은 "처음 보는 거래"
        when(guard.markIfFirstSeen(anyString())).thenReturn(true);
    }

    private ConsumerRecord<String, String> record(String json) {
        return new ConsumerRecord<>("payment.completed", 0, 0L, "PI-1", json);
    }

    private String completedEvent() {
        return """
               {"paymentInstructionId":"PI-1","status":"COMPLETED",
                "amount":15000000,"completedAt":"2026-08-08T01:00:05Z"}
               """;
    }

    private PaymentDetail detail() {
        return new PaymentDetail("PI-1", "TXN-1", "COMPLETED", "9001", "110-222-333333",
                "004", "999-888-777777", "홍길동", 15_000_000L, false, "KFTC", "MOBILE",
                OffsetDateTime.parse("2026-08-08T01:00:00Z"),
                OffsetDateTime.parse("2026-08-08T01:00:05Z"), "20260808");
    }

    private double counter(String name, String... tags) {
        return registry.get(name).tags(tags).counter().count();
    }

    @Test
    @DisplayName("정상 이벤트는 보강 후 처리로 세어진다")
    void processesCompletedEvent() {
        when(detailClient.fetch("PI-1")).thenReturn(Optional.of(detail()));

        consumer.consume(record(completedEvent()), ack);

        assertThat(counter("fds_event_consumed_total", "outcome", "processed")).isEqualTo(1.0);
        verify(ack, times(1)).acknowledge();
    }

    @Test
    @DisplayName("같은 거래가 다시 오면 처리하지 않는다 — 건수 룰이 부풀지 않아야 한다")
    void duplicateIsNotProcessed() {
        when(guard.markIfFirstSeen("PI-1")).thenReturn(false);

        consumer.consume(record(completedEvent()), ack);

        assertThat(counter("fds_event_consumed_total", "outcome", "duplicate")).isEqualTo(1.0);
        // 중복이면 보강 조회도 하지 않는다 — 결제계에 불필요한 부하를 주지 않는다.
        verify(detailClient, never()).fetch(anyString());
        verify(ack, times(1)).acknowledge();
    }

    @Test
    @DisplayName("보강 실패는 따로 세어 눈에 보이게 한다")
    void enrichFailureIsCounted() {
        when(detailClient.fetch("PI-1")).thenReturn(Optional.empty());

        consumer.consume(record(completedEvent()), ack);

        // 보강이 안 되면 금액 말고는 판정할 수 없다. 조용히 지나가면
        // "탐지가 돌고 있는데 아무것도 안 잡힌다" 가 된다.
        assertThat(counter("fds_enrich_failed_total")).isEqualTo(1.0);
        assertThat(counter("fds_event_consumed_total", "outcome", "unenriched")).isEqualTo(1.0);
        verify(ack, times(1)).acknowledge();
    }

    @Test
    @DisplayName("COMPLETED 가 아닌 이벤트는 넘긴다 — 같은 토픽에 다른 상태도 온다")
    void nonCompletedIsSkipped() {
        consumer.consume(record("""
                {"paymentInstructionId":"PI-1","status":"REVERSED","amount":100}
                """), ack);

        verify(detailClient, never()).fetch(anyString());
        verify(ack, times(1)).acknowledge();
    }

    @Test
    @DisplayName("깨진 payload 여도 ack 한다 — 한 건에 막혀 스트림이 서면 안 된다")
    void malformedPayloadStillAcknowledges() {
        consumer.consume(record("{ this is not json"), ack);

        assertThat(counter("fds_event_consumed_total", "outcome", "error")).isEqualTo(1.0);
        verify(ack, times(1)).acknowledge();
    }
}
