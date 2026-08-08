package com.bank.fds.consumer;

import com.bank.fds.detect.PostHocDetectionService;
import com.bank.fds.enrich.PaymentDetail;
import com.bank.fds.enrich.PaymentDetailClient;
import com.bank.fds.observability.FdsMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@code payment.completed} 를 구독해 탐지를 돌린다.
 *
 * <p><b>결제계를 고치지 않는다.</b> 이 토픽은 이미 회계계 등이 쓰고 있고, 여기서는
 * <b>별도 컨슈머 그룹</b>으로 하나 더 구독할 뿐이다. 그래서 탐지기가 죽어도
 * 결제와 기존 소비자는 그대로 돈다.
 *
 * <p><b>이벤트 하나로는 부족하다.</b> payload 는 piId·status·amount·completedAt 뿐이라
 * 금액 룰 말고는 판정할 수 없다. 나머지는 결제계 내부 API 로 보강한다.
 *
 * <p><b>ack 는 어떤 경로로 끝나든 한 번만 한다.</b> 탐지 실패는 재처리해도 대개 같은
 * 결과이고, ack 를 안 하면 같은 이벤트에 막혀 그 뒤 거래를 통째로 놓친다 —
 * 한 건을 못 잡는 것보다 나쁘다. 대신 왜 못 했는지는 지표로 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCompletedConsumer {

    private final ObjectMapper objectMapper;
    private final PaymentDetailClient detailClient;
    private final ProcessedEventGuard processedEventGuard;
    private final PostHocDetectionService detectionService;
    private final FdsMetrics metrics;

    @KafkaListener(
            topics = "${fds.kafka.topic:payment.completed}",
            groupId = "${fds.kafka.consumer-group:fds-detector}"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            handle(record);
        } catch (Exception e) {
            // 여기까지 온 예외는 대개 payload 형식 문제다. 재처리해도 같은 결과이므로
            // 세어 두고 넘어간다 — 한 건에 막혀 스트림 전체를 세우지 않는다.
            log.error("이벤트 처리 실패 key={} value={} reason={}",
                    record.key(), record.value(), e.toString());
            metrics.eventConsumed("error");
        } finally {
            ack.acknowledge();
        }
    }

    private void handle(ConsumerRecord<String, String> record) throws Exception {
        JsonNode payload = objectMapper.readTree(record.value());

        // KFTC_SETTLED·BOK_CONFIRMED 도 같은 토픽으로 온다(결제계 OutboxPublisher 매핑).
        // 수신측이 상태로 분기하는 구조라 여기서도 확인한다.
        if (!"COMPLETED".equals(payload.path("status").asText())) {
            return;
        }

        String piId = payload.path("paymentInstructionId").asText();
        if (piId.isBlank()) {
            log.warn("paymentInstructionId 없는 이벤트 skip key={}", record.key());
            return;
        }

        if (!processedEventGuard.markIfFirstSeen(piId)) {
            // 재처리는 정상 상황이다(at-least-once). 조용히 넘기되 세어는 둔다.
            metrics.eventConsumed("duplicate");
            return;
        }

        Optional<PaymentDetail> detail = detailClient.fetch(piId);
        if (detail.isEmpty()) {
            // 보강 실패는 룰이 조용히 못 잡게 되는 경로다. 반드시 눈에 보여야 한다.
            metrics.enrichFailed();
            metrics.eventConsumed("unenriched");
            return;
        }

        detectionService.detect(detail.get());

        metrics.eventConsumed("processed");
    }
}
