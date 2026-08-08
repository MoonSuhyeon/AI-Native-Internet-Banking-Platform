package com.bank.customer.notification;

import com.bank.customer.notification.consumer.PaymentDelayedConsumer;
import com.bank.customer.notification.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 이상거래 지연 알림 소비.
 *
 * <p><b>이 알림이 없으면 지연 장치는 정상 거래를 30분 불편하게 만드는 것에 그친다.</b>
 * 고객이 알아채고 취소할 수 있어야 비로소 사고를 막는다.
 *
 * <p>여기서 지키는 것은 두 가지다. 알림이 실제로 만들어지는가, 그리고 한 건이
 * 실패해도 컨슈머가 서지 않는가. 후자가 무너지면 그 뒤 알림을 전부 놓치는데,
 * 알림은 원래 조용해서 아무도 눈치채지 못한다.
 */
class PaymentDelayedConsumerTest {

    private NotificationService notificationService;
    private PaymentDelayedConsumer consumer;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        consumer = new PaymentDelayedConsumer(new ObjectMapper(), notificationService);
    }

    private ConsumerRecord<String, String> record(String json) {
        return new ConsumerRecord<>("payment.delayed", 0, 0L, "PI-1", json);
    }

    private static final String VALID = """
            {"paymentInstructionId":"PI-1","customerId":"9001",
             "scheduledExecutionAt":"2026-08-08T05:30:00Z","reason":"FDS_DELAY"}
            """;

    @Test
    @DisplayName("지연 이벤트를 받으면 고객 알림을 만든다")
    void createsNotification() {
        consumer.consume(record(VALID));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(notificationService).notify(
                org.mockito.ArgumentMatchers.eq(9001L),
                org.mockito.ArgumentMatchers.eq("PAYMENT_DELAYED"),
                anyString(), body.capture(),
                org.mockito.ArgumentMatchers.eq("PI-1"),
                org.mockito.ArgumentMatchers.eq(OffsetDateTime.parse("2026-08-08T05:30:00Z")));

        assertThat(body.getValue())
                .as("무엇을 해야 하는지 알려주지 않으면 알림이 소용없다")
                .contains("취소");
    }

    @Test
    @DisplayName("대상 고객이 없으면 알림을 만들지 않는다 — 누구에게 보낼지 모른다")
    void missingCustomerIsSkipped() {
        consumer.consume(record("""
                {"paymentInstructionId":"PI-1","scheduledExecutionAt":"2026-08-08T05:30:00Z"}
                """));

        verify(notificationService, never()).notify(anyLong(), anyString(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("깨진 메시지에도 컨슈머가 서지 않는다 — 서면 그 뒤 알림을 전부 놓친다")
    void malformedMessageDoesNotStopTheConsumer() {
        assertThatCode(() -> consumer.consume(record("{ not json"))).doesNotThrowAnyException();

        verify(notificationService, never()).notify(anyLong(), anyString(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    @DisplayName("알림 저장이 터져도 컨슈머가 서지 않는다")
    void downstreamFailureDoesNotStopTheConsumer() {
        // DB 장애나 제약 위반은 런타임 예외로 올라온다. 그대로 두면 컨슈머가 같은
        // 메시지에 막혀 그 뒤 알림을 전부 놓친다 — 알림은 원래 조용해서
        // 멈춘 것을 아무도 눈치채지 못한다.
        org.mockito.Mockito.doThrow(new IllegalStateException("DB down"))
                .when(notificationService)
                .notify(anyLong(), anyString(), anyString(), anyString(), anyString(), any());

        assertThatCode(() -> consumer.consume(record(VALID))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("고객번호가 숫자가 아니어도 컨슈머가 서지 않는다")
    void nonNumericCustomerDoesNotStopTheConsumer() {
        assertThatCode(() -> consumer.consume(record("""
                {"paymentInstructionId":"PI-1","customerId":"abc"}
                """))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("실행 시각이 없어도 알림은 만든다 — 알리는 것이 더 중요하다")
    void missingDueDateStillNotifies() {
        consumer.consume(record("""
                {"paymentInstructionId":"PI-1","customerId":"9001"}
                """));

        verify(notificationService).notify(anyLong(), anyString(), anyString(),
                anyString(), anyString(), org.mockito.ArgumentMatchers.isNull());
    }
}
