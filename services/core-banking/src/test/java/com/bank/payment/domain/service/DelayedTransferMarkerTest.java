package com.bank.payment.domain.service;

import com.bank.payment.common.IdGenerator;
import com.bank.payment.common.LedgerFailureSimulator;
import com.bank.payment.domain.OutboxMessage;
import com.bank.payment.domain.mapper.BokSettlementTransactionMapper;
import com.bank.payment.domain.mapper.ExternalCallMapper;
import com.bank.payment.domain.mapper.IdempotencyKeyMapper;
import com.bank.payment.domain.mapper.KftcClearingTransactionMapper;
import com.bank.payment.domain.mapper.LedgerMapper;
import com.bank.payment.domain.mapper.OutboxMessageMapper;
import com.bank.payment.domain.mapper.PaymentInstructionMapper;
import com.bank.payment.domain.mapper.StatusHistoryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 지연 예약이 남기는 흔적.
 *
 * <p><b>두 가지가 남아야 한다.</b>
 *
 * <p>1) <b>표시</b> — 지연 장치의 성능은 "얼마나 지연시켰는가" 가 아니라 지연된 건 중
 * 실제로 취소된 비율로 드러난다. 표시가 없으면 취소를 세도 그게 지연 때문인지
 * 고객이 원래 예약해 둔 거래를 무른 것인지 구별할 수 없고, 두 값을 섞으면 비율이
 * 무의미해진다.
 *
 * <p>2) <b>알림 이벤트</b> — 지연시켜 놓고 아무도 모르면 시간만 지나고 그대로 나간다.
 * 고객이 알아채고 취소할 수 있어야 비로소 이 장치가 뜻을 갖는다.
 *
 * <p>둘 다 빠져도 예외는 나지 않는다. 거래는 정상적으로 예약되고 화면도 멀쩡하다.
 */
class DelayedTransferMarkerTest {

    private OutboxMessageMapper outboxMessageMapper;
    private PaymentInstructionMapper paymentInstructionMapper;
    private StatusHistoryMapper statusHistoryMapper;
    private PaymentTransactionService service;

    @BeforeEach
    void setUp() {
        outboxMessageMapper = mock(OutboxMessageMapper.class);
        paymentInstructionMapper = mock(PaymentInstructionMapper.class);
        statusHistoryMapper = mock(StatusHistoryMapper.class);
        IdGenerator idGenerator = mock(IdGenerator.class);

        when(paymentInstructionMapper.updateScheduled(anyString(), any(), anyInt())).thenReturn(1);
        when(paymentInstructionMapper.selectById(anyString())).thenReturn(
                com.bank.payment.domain.PaymentInstruction.builder().senderUserId("9001").build());
        when(statusHistoryMapper.selectMaxSequence(anyString())).thenReturn(1);
        when(idGenerator.nextHistoryId()).thenReturn("HIST-1");
        when(idGenerator.nextMessageId()).thenReturn("MSG-1");

        service = new PaymentTransactionService(
                paymentInstructionMapper,
                mock(TransferFeePolicy.class),
                mock(IdempotencyKeyMapper.class),
                statusHistoryMapper,
                mock(ExternalCallMapper.class),
                idGenerator,
                mock(LedgerMapper.class),
                outboxMessageMapper,
                mock(KftcClearingTransactionMapper.class),
                mock(BokSettlementTransactionMapper.class),
                new ObjectMapper(),
                mock(LedgerFailureSimulator.class));
    }

    private static final OffsetDateTime EXECUTE_AT =
            OffsetDateTime.parse("2026-08-08T05:30:00Z");

    @Test
    @DisplayName("지연 예약은 고객에게 알릴 이벤트를 남긴다")
    void delayedScheduleEmitsNotificationEvent() {
        service.markScheduled("PI-1", 2, EXECUTE_AT,
                PaymentTransactionService.TRIGGER_FDS_DELAY);

        ArgumentCaptor<OutboxMessage> captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(outboxMessageMapper).insert(captor.capture());

        OutboxMessage msg = captor.getValue();
        assertThat(msg.getEventType()).isEqualTo("PAYMENT_DELAYED");
        assertThat(msg.getPayload())
                .as("언제 실행되는지 알려야 고객이 취소를 판단할 수 있다")
                .contains("PI-1")
                .contains("2026-08-08T05:30");
        assertThat(msg.getPayload())
                .as("누구에게 보낼지가 없으면 고객계는 알림을 만들 수 없다")
                .contains("9001");
    }

    @Test
    @DisplayName("보통의 예약이체는 알림 이벤트를 내지 않는다")
    void ordinaryScheduleDoesNotEmitDelayEvent() {
        // 고객이 스스로 예약한 거래까지 "지연됐다" 고 알리면 알림이 무의미해지고,
        // 취소 비율도 함께 망가진다.
        service.markScheduled("PI-2", 2, EXECUTE_AT,
                PaymentTransactionService.TRIGGER_USER);

        verify(outboxMessageMapper, never()).insert(any());
    }

    @Test
    @DisplayName("기존 호출부는 사용자 예약으로 남는다 — 모든 예약이 지연 건이 되면 안 된다")
    void legacyOverloadDefaultsToUser() {
        service.markScheduled("PI-3", 2, EXECUTE_AT);

        verify(outboxMessageMapper, never()).insert(any());
    }
}
