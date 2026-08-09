package com.bank.loan.advisory.listener;

import com.bank.loan.advisory.event.QuarantineTriggeredEvent;
import com.bank.loan.notification.channel.StubOperatorAlertAdapter;
import com.bank.loan.notification.outbox.NotificationOutboxAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class QuarantineNotificationListenerTest {

    NotificationOutboxAppender outboxAppender = mock(NotificationOutboxAppender.class);
    QuarantineNotificationListener listener;

    @BeforeEach
    void setUp() {
        listener = new QuarantineNotificationListener(outboxAppender);
    }

    @Test
    void BIAS_SUSPECTED_이벤트_수신_시_OP_ALERT_outbox_적재() {
        QuarantineTriggeredEvent event = new QuarantineTriggeredEvent(
                9001L, 201L, "BIAS_SUSPECTED", "BIAS_DETECTION", List.of(501L, 502L));

        listener.onQuarantineTriggered(event);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxAppender).enqueue(
                eq(QuarantineNotificationListener.EVENT_TYPE),
                eq(9001L),
                eq(StubOperatorAlertAdapter.CHANNEL_CD),
                payloadCaptor.capture());

        String payload = payloadCaptor.getValue();
        assertThat(payload).contains("\"revId\":9001");
        assertThat(payload).contains("\"reviewerId\":201");
        assertThat(payload).contains("\"conclusionCd\":\"BIAS_SUSPECTED\"");
        assertThat(payload).contains("\"analysisType\":\"BIAS_DETECTION\"");
        assertThat(payload).contains("501");
    }

    @Test
    void VIOLATION_SUSPECTED_이벤트도_동일하게_적재() {
        QuarantineTriggeredEvent event = new QuarantineTriggeredEvent(
                9002L, 203L, "VIOLATION_SUSPECTED", "COMPLIANCE_VERIFICATION", List.of(503L));

        listener.onQuarantineTriggered(event);

        verify(outboxAppender).enqueue(
                eq(QuarantineNotificationListener.EVENT_TYPE),
                eq(9002L),
                eq(StubOperatorAlertAdapter.CHANNEL_CD),
                any());
    }

    @Test
    void reviewerId_null_이어도_예외_없이_적재() {
        QuarantineTriggeredEvent event = new QuarantineTriggeredEvent(
                9003L, null, "BIAS_SUSPECTED", "BIAS_DETECTION", List.of(504L));

        assertThatNoException().isThrownBy(() -> listener.onQuarantineTriggered(event));
        verify(outboxAppender).enqueue(any(), any(), any(), any());
    }

    @Test
    void notification_outbox_적재_실패해도_예외_전파_안함() {
        doThrow(new RuntimeException("DB 연결 오류"))
                .when(outboxAppender).enqueue(any(), any(), any(), any());

        QuarantineTriggeredEvent event = new QuarantineTriggeredEvent(
                9004L, 201L, "BIAS_SUSPECTED", "BIAS_DETECTION", List.of(505L));

        assertThatNoException().isThrownBy(() -> listener.onQuarantineTriggered(event));
    }
}
