package com.bank.ai.bias.consumer;

import com.bank.ai.bias.dto.BiasCheckPayload;
import com.bank.ai.bias.dto.BiasReportCallbackRequest;
import com.bank.ai.bias.service.BiasCheckAgentService;
import com.bank.ai.review.client.LoanServiceClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * loan-domain-events 토픽에서 BIAS_CHECK_REQUESTED 이벤트를 소비하는 리스너.
 *
 * <p>흐름:
 * <ol>
 *   <li>JSON 역직렬화 — eventTypeCd != BIAS_CHECK_REQUESTED 이면 즉시 ACK 후 스킵</li>
 *   <li>BiasCheckAgentService.analyze() — 규칙 기반 탐지 + LLM 요약</li>
 *   <li>LoanServiceClient.reportBias() — POST /api/internal/loan-reviews/{revId}/bias-report 콜백</li>
 *   <li>예외 시 ERROR 로그 + ACK (Kafka 재처리 루프 방지 — 재시도는 loan-service 어드민 UI에서)</li>
 * </ol>
 *
 * <p>멱등성: loan-service 의 {@code LoanReviewBiasReportService.append()} 가
 * BIAS_REVIEWING 상태가 아닌 경우 severity 캐시를 갱신하지 않아 재전송을 무시한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BiasCheckKafkaConsumer {

    private final ObjectMapper objectMapper;
    private final BiasCheckAgentService biasCheckAgentService;
    private final LoanServiceClient loanServiceClient;

    @Async("llmExecutor")
    @KafkaListener(
            topics = "loan-domain-events",
            groupId = "${spring.kafka.consumer.group-id:auto-loan-review-bias}",
            containerFactory = "biasKafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String payload = record.value();
        log.debug("BiasCheckKafkaConsumer: received key={} partition={} offset={}",
                record.key(), record.partition(), record.offset());

        try {
            BiasCheckPayload event = objectMapper.readValue(payload, BiasCheckPayload.class);

            // BIAS_CHECK_REQUESTED 이외 이벤트 타입은 스킵
            if (!BiasCheckPayload.EVENT_TYPE_CD.equals(event.eventTypeCd())) {
                log.trace("BiasCheckKafkaConsumer: skip eventTypeCd={}", event.eventTypeCd());
                ack.acknowledge();
                return;
            }

            log.info("BiasCheckKafkaConsumer: processing BIAS_CHECK_REQUESTED revId={} applId={}",
                    event.revId(), event.applId());

            BiasReportCallbackRequest report = biasCheckAgentService.analyze(event);
            loanServiceClient.reportBias(event.revId(), report);

            log.info("BiasCheckKafkaConsumer: callback sent revId={} severity={}",
                    event.revId(), report.severityCd());

        } catch (JsonProcessingException e) {
            log.error("BiasCheckKafkaConsumer: JSON 역직렬화 실패 key={} — 스킵", record.key(), e);
        } catch (Exception e) {
            log.error("BiasCheckKafkaConsumer: 처리 실패 key={} — 스킵 (loan-service 어드민에서 재시도 가능)",
                    record.key(), e);
        } finally {
            ack.acknowledge();
        }
    }
}
