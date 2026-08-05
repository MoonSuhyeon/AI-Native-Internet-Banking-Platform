package com.bank.payment.outbound.kafka;

import com.bank.payment.config.PaymentMetrics;
import com.bank.payment.domain.OutboxMessage;
import com.bank.payment.domain.service.PaymentOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Transactional Outbox 워커 (토픽정의서 v3.1 R10 / P-028).
 *
 * outbox_message.publish_status=PENDING 레코드를 1초마다 폴링해 Kafka로 발행한다.
 * ★ @Transactional 금지: Kafka send()가 DB 트랜잭션 안에 들어가면 P-028 위반.
 *    DB 상태 업데이트는 OutboxTransactionHelper(별도 Bean)에 위임한다.
 * ★ KafkaTemplate.send() 직접 호출은 이 클래스에서만 허용 (다른 곳 금지).
 */
@Slf4j
@Component
public class OutboxPublisher {

    private static final int STUCK_PUBLISHING_MINUTES = 5;

    private final OutboxTransactionHelper transactionHelper;
    private final KafkaTemplate<String, String> kftcKafkaTemplate;
    private final KafkaTemplate<String, String> bokKafkaTemplate;
    private final KafkaTemplate<String, String> internalKafkaTemplate;
    private final ObjectMapper objectMapper;
    private final PaymentOrchestrator orchestrator;
    private final PaymentMetrics metrics;

    public OutboxPublisher(
            OutboxTransactionHelper transactionHelper,
            @Qualifier("kftcKafkaTemplate") KafkaTemplate<String, String> kftcKafkaTemplate,
            @Qualifier("bokKafkaTemplate") KafkaTemplate<String, String> bokKafkaTemplate,
            @Qualifier("internalKafkaTemplate") KafkaTemplate<String, String> internalKafkaTemplate,
            ObjectMapper objectMapper,
            PaymentOrchestrator orchestrator,
            PaymentMetrics metrics) {
        this.transactionHelper = transactionHelper;
        this.kftcKafkaTemplate = kftcKafkaTemplate;
        this.bokKafkaTemplate = bokKafkaTemplate;
        this.internalKafkaTemplate = internalKafkaTemplate;
        this.objectMapper = objectMapper;
        this.orchestrator = orchestrator;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelay = 1000)
    public void publishPending() {
        List<OutboxMessage> pending = transactionHelper.claimPending();
        if (pending.isEmpty()) {
            return;
        }
        log.debug("Outbox 발행 대상: {}건", pending.size());
        for (OutboxMessage message : pending) {
            publishOne(message);
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void recoverStuckPublishing() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(STUCK_PUBLISHING_MINUTES);
        int recovered = transactionHelper.resetStuckPublishing(cutoff);
        if (recovered > 0) {
            log.warn("Outbox Stuck PUBLISHING 복구: {}건 → PENDING 재설정 (cutoff={}분)", recovered, STUCK_PUBLISHING_MINUTES);
        }
    }

    private void publishOne(OutboxMessage message) {
        String messageId = message.getMessageId();
        String eventType = message.getEventType();
        String piId = message.getPaymentInstructionId();
        String payload = message.getPayload();

        String topicName = resolveTopicName(eventType);
        if (topicName == null) {
            log.warn("Outbox 미매핑 event_type — skip: messageId={}, eventType={}", messageId, eventType);
            return;
        }

        KafkaTemplate<String, String> template = resolveTemplate(topicName);

        try {
            // 동기 발행: acks=all 완료 확인 후 markSent. 비동기로 markSent 먼저 하면 유실 위험.
            String recordKey = resolveRecordKey(topicName, piId, payload, messageId, eventType);
            template.send(topicName, recordKey, payload).get();
            transactionHelper.markSent(messageId);
            metrics.outboxPublished();
            log.debug("Outbox 발행 완료: messageId={}, topic={}, piId={}", messageId, topicName, piId);
        } catch (ExecutionException e) {
            String lastError = truncate(e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            log.error("Outbox 발행 실패: messageId={}, topic={}, error={}", messageId, topicName, lastError, e);
            metrics.outboxFailed();
            if ("KFTC_REQUEST_SENT".equals(eventType)) {
                triggerF4Compensation(piId, messageId, lastError);
            } else if ("BOK_REQUEST_SENT".equals(eventType)) {
                triggerBokF4Compensation(piId, messageId, lastError);
            } else {
                transactionHelper.markFailed(messageId, lastError);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Outbox 발행 인터럽트: messageId={}", messageId, e);
            if ("KFTC_REQUEST_SENT".equals(eventType)) {
                triggerF4Compensation(piId, messageId, "INTERRUPTED");
            } else if ("BOK_REQUEST_SENT".equals(eventType)) {
                triggerBokF4Compensation(piId, messageId, "INTERRUPTED");
            } else {
                transactionHelper.markFailed(messageId, "INTERRUPTED");
            }
        }
    }

    /**
     * F4 KFTC 송신실패 자동보상 트리거. KFTC_REQUEST_SENT 발행 실패 시에만 호출.
     * 보상 성공/실패와 무관하게 예외를 루프 밖으로 던지지 않아 다른 Outbox 레코드 격리 유지.
     */
    private void triggerF4Compensation(String piId, String messageId, String lastError) {
        log.error("[F4] KFTC 송신 실패 → 자동보상 트리거. piId={} err={}", piId, lastError);
        metrics.compensation("F4_KFTC");
        try {
            orchestrator.processPublishFailure(piId, lastError);
            transactionHelper.markFailed(messageId, lastError);
        } catch (Exception ce) {
            log.error("[F4] 보상 실패 — 수동개입 필요. piId={}", piId, ce);
            transactionHelper.markFailed(messageId, "COMPENSATION_FAILED: " + truncate(ce.getMessage()));
        }
    }

    /**
     * F4 BOK 송신실패 자동보상 트리거. BOK_REQUEST_SENT 발행 실패 시에만 호출.
     * KFTC triggerF4Compensation과 동형. 다른 Outbox 레코드 격리 유지.
     */
    private void triggerBokF4Compensation(String piId, String messageId, String lastError) {
        log.error("[BOK F4] BOK 송신 실패 → 자동보상 트리거. piId={} err={}", piId, lastError);
        metrics.compensation("F4_BOK");
        try {
            orchestrator.processBokPublishFailure(piId, lastError);
            transactionHelper.markFailed(messageId, lastError);
        } catch (Exception ce) {
            log.error("[BOK F4] 보상 실패 — 수동개입 필요. piId={}", piId, ce);
            transactionHelper.markFailed(messageId, "COMPENSATION_FAILED: " + truncate(ce.getMessage()));
        }
    }

    /**
     * event_type → Kafka 토픽명 매핑 (토픽정의서 v3.1 시트10).
     * null 반환 = 미매핑(skip).
     */
    private String resolveTopicName(String eventType) {
        return switch (eventType) {
            case "PAYMENT_COMPLETED" -> "payment.completed";
            case "PAYMENT_FAILED"    -> "payment.failed";
            case "PAYMENT_REVERSED"  -> "payment.reversed";
            case "KFTC_SETTLED"      -> "payment.completed"; // 회계계 P-001 unwind 트리거. 수신측이 eventType으로 분기
            case "BOK_CONFIRMED"     -> "payment.completed"; // KFTC_SETTLED 대칭. 수신측이 eventType으로 분기
            case "KFTC_REQUEST_SENT"      -> "kftc.network.request";
            case "KFTC_ACK_SENT"          -> "kftc.network.response";
            case "KFTC_SETTLEMENT_SENT"   -> "kftc.network.response";
            case "KFTC_REJECT_SENT"       -> "kftc.network.response";
            case "BOK_REQUEST_SENT"  -> "bok.network.request";
            case "BOK_ACK_SENT"     -> "bok.network.response";
            case "BOK_CONFIRM_SENT" -> "bok.network.response";
            case "BOK_REJECT_SENT"  -> "bok.network.response";
            default -> null;
        };
    }

    /**
     * kftc.network.* → clearingNo, bok.network.* → bokReferenceNo, 그 외 → piId.
     * payload에 대상 키가 없으면 piId fallback (+warn).
     */
    private String resolveRecordKey(String topicName, String piId, String payload,
                                    String messageId, String eventType) {
        if (topicName.startsWith("kftc.")) {
            try {
                String clearingNo = objectMapper.readTree(payload).path("clearingNo").asText();
                if (!clearingNo.isEmpty()) {
                    return clearingNo;
                }
            } catch (Exception e) {
                log.warn("Outbox record key 파싱 실패(kftc) — piId fallback: messageId={}", messageId, e);
            }
            log.warn("Outbox clearingNo 없음 — piId fallback: messageId={} eventType={}", messageId, eventType);
            return piId;
        }
        if (topicName.startsWith("bok.")) {
            try {
                String bokReferenceNo = objectMapper.readTree(payload).path("bokReferenceNo").asText();
                if (!bokReferenceNo.isEmpty()) {
                    return bokReferenceNo;
                }
            } catch (Exception e) {
                log.warn("Outbox record key 파싱 실패(bok) — piId fallback: messageId={}", messageId, e);
            }
            log.warn("Outbox bokReferenceNo 없음 — piId fallback: messageId={} eventType={}", messageId, eventType);
            return piId;
        }
        return piId;
    }

    /** 토픽명 prefix → 클러스터 KafkaTemplate 선택 */
    private KafkaTemplate<String, String> resolveTemplate(String topicName) {
        if (topicName.startsWith("kftc.")) return kftcKafkaTemplate;
        if (topicName.startsWith("bok."))  return bokKafkaTemplate;
        return internalKafkaTemplate;  // payment.* 포함 기본값
    }

    /** last_error VARCHAR(500) 제약에 맞게 자름 */
    private String truncate(String s) {
        if (s == null) return "null";
        return s.length() > 497 ? s.substring(0, 497) + "..." : s;
    }
}
