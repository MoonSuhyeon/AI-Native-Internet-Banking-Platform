package com.bank.customer.notification.consumer;

import com.bank.customer.notification.service.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * 이상거래로 지연된 이체를 고객에게 알린다.
 *
 * <p><b>왜 결제계가 직접 보내지 않는가.</b> 결제 트랜잭션이 외부 발송 성공에 묶이면
 * 알림 장애가 곧 이체 장애가 된다. 결제계는 이벤트만 내고, 연락처와 수신 설정을 가진
 * 고객계가 발송을 판단한다.
 *
 * <p><b>왜 알려야 하는가.</b> 지연은 고객이 알아채고 취소할 시간을 주는 장치다.
 * 알리지 않으면 30분이 그냥 지나고 그대로 나간다 — 정상 거래만 불편하게 만든 셈이 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentDelayedConsumer {

    private static final String TYPE = "PAYMENT_DELAYED";

    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;

    @KafkaListener(
            topics = "${customer.kafka.payment-delayed-topic:payment.delayed}",
            groupId = "${customer.kafka.consumer-group:customer-notification}"
    )
    public void consume(ConsumerRecord<String, String> record) {
        try {
            JsonNode payload = objectMapper.readTree(record.value());

            String piId = payload.path("paymentInstructionId").asText();
            String customerId = payload.path("customerId").asText();
            String executeAt = payload.path("scheduledExecutionAt").asText();

            if (piId.isBlank() || customerId.isBlank()) {
                // 누구에게 보낼지 모르면 알림을 만들 수 없다. 조용히 넘기지 않고 남긴다 —
                // 이벤트 계약이 바뀐 신호일 수 있다.
                log.warn("지연 알림에 필요한 값이 없다 piId={} customerId={}", piId, customerId);
                return;
            }

            OffsetDateTime dueAt = executeAt.isBlank() ? null : OffsetDateTime.parse(executeAt);

            boolean created = notificationService.notify(
                    Long.valueOf(customerId),
                    TYPE,
                    "이체가 잠시 보류되었습니다",
                    "이상거래 점검으로 이체 실행이 미뤄졌습니다. 본인이 요청한 거래가 아니면 "
                            + "실행 전에 취소해 주세요.",
                    piId,
                    dueAt);

            if (created) {
                log.info("지연 알림 생성 piId={} customerId={}", piId, customerId);
            }
        } catch (Exception e) {
            // 여기서 예외를 던지면 컨슈머가 같은 메시지에 막혀 그 뒤 알림을 전부 놓친다.
            // 한 건을 못 보내는 것보다 알림이 통째로 멈추는 쪽이 나쁘다.
            log.error("지연 알림 처리 실패 value={} reason={}", record.value(), e.toString());
        }
    }
}
