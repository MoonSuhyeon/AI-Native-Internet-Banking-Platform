package com.bank.loan.document.kafka;

import com.bank.loan.document.service.DocAgentEventService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * doc-agent 이벤트 구독.
 *
 * <p>업로드 응답으로 끝나지 않는 판정을 받는다.
 * <ul>
 *   <li>{@code doc-agent.routed} — HOLD 로 남았던 서류의 검증 상태 재판정</li>
 *   <li>{@code doc-agent.fraud.audit} — 위변조 확정에 따른 신청 거절</li>
 * </ul>
 *
 * <p>파싱 실패나 필수 필드 누락은 재시도해도 같은 결과이므로 ack 하고 버린다.
 * 처리 중 예외만 다시 던져 재시도 → DLT 경로를 타게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocAgentEventConsumer {

    static final String TOPIC_ROUTED      = "doc-agent.routed";
    static final String TOPIC_FRAUD_AUDIT = "doc-agent.fraud.audit";

    private final ObjectMapper objectMapper;
    private final DocAgentEventService docAgentEventService;

    @KafkaListener(
            topics = {TOPIC_ROUTED, TOPIC_FRAUD_AUDIT},
            groupId = "loan-service-doc-agent-event",
            containerFactory = "docAgentEventListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        JsonNode node;
        try {
            node = objectMapper.readTree(record.value());
        } catch (Exception e) {
            log.error("doc-agent 이벤트 파싱 실패 — 폐기 topic={} key={}",
                    record.topic(), record.key(), e);
            ack.acknowledge();
            return;
        }

        try {
            if (TOPIC_FRAUD_AUDIT.equals(record.topic())) {
                handleFraudAudit(node);
            } else {
                handleRouted(node);
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("doc-agent 이벤트 처리 실패 topic={} key={}",
                    record.topic(), record.key(), e);
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    private void handleRouted(JsonNode node) {
        String submissionId = node.path("submission_id").asText("");
        String verifyStatus = node.path("verify_status").asText("");
        if (submissionId.isBlank() || verifyStatus.isBlank()) {
            log.warn("doc-agent.routed — 필수 필드 누락, 폐기: {}", node);
            return;
        }
        docAgentEventService.handleRouted(submissionId, verifyStatus);
    }

    private void handleFraudAudit(JsonNode node) {
        if (!"FRAUD_CONFIRMED".equals(node.path("event_type").asText(""))) {
            return; // 확정 외 단계(검토 착수 등)는 신청 상태를 건드리지 않는다.
        }
        String applNo = node.path("application_id").asText("");
        if (applNo.isBlank()) {
            log.warn("doc-agent.fraud.audit — application_id 누락, 폐기: {}", node);
            return;
        }
        docAgentEventService.handleFraudConfirmed(applNo, node.path("retention_until").asText(null));
    }
}
