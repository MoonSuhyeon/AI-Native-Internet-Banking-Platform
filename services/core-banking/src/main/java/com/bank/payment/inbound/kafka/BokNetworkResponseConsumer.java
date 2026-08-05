package com.bank.payment.inbound.kafka;

import com.bank.payment.config.PaymentMetrics;
import com.bank.payment.domain.BokSettlementTransaction;
import com.bank.payment.domain.PaymentInstruction;
import com.bank.payment.domain.service.PaymentOrchestrator;
import com.bank.payment.domain.service.PaymentTransactionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * BOK 망 응답 Consumer. @EnableKafka는 KftcKafkaConfig에 선언돼 있으므로 재선언 금지.
 * SETTLEMENT_COMPLETED: txService 직접 호출 (외부호출 없음, P-028).
 * SETTLEMENT_REJECT(F3): orchestrator.processBokReject 경유 (B-5 외부호출 포함, P-028).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BokNetworkResponseConsumer {

    private final ObjectMapper objectMapper;
    private final PaymentTransactionService txService;
    private final PaymentOrchestrator orchestrator;
    private final PaymentMetrics metrics;

    @KafkaListener(
            topics = "bok.network.response",
            containerFactory = "bokListenerContainerFactory",
            groupId = "${payment.kafka.bok.consumer-group}"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        JsonNode payload = objectMapper.readTree(record.value());
        String messageType = payload.path("messageType").asText();

        switch (messageType) {
            case "SETTLEMENT_COMPLETED": {
                String bokReferenceNo = payload.path("bokReferenceNo").asText();
                String responseCode   = payload.path("responseCode").asText();

                BokSettlementTransaction bst = txService.selectByBokReferenceNo(bokReferenceNo);
                if (bst == null) {
                    log.error("[BOK] SETTLEMENT_COMPLETED BST 없음, skip. bokReferenceNo={}", bokReferenceNo);
                    break;
                }
                String piId = bst.getOurPaymentInstructionId();
                PaymentInstruction pi = txService.selectById(piId);
                if (pi == null) {
                    log.error("[BOK] SETTLEMENT_COMPLETED PI 없음, skip. bokReferenceNo={} piId={}",
                            bokReferenceNo, piId);
                    break;
                }

                // 멱등 가드 1: 이미 COMPLETED (중복수신) — 정상완결 후 뒤늦은 실패통보도 여기서 skip
                if ("COMPLETED".equals(pi.getStatus())) {
                    log.info("[BOK] SETTLEMENT_COMPLETED COMPLETED skip. piId={}", piId);
                    break;
                }
                // 멱등 가드 2: CLEARING 아닌 상태 (예상치 못한 전이)
                if (!"CLEARING".equals(pi.getStatus())) {
                    log.warn("[BOK] SETTLEMENT_COMPLETED !CLEARING skip. piId={} status={}",
                            piId, pi.getStatus());
                    break;
                }

                if ("0000".equals(responseCode)) {
                    String settledAt      = payload.path("settledAt").asText();
                    String settlementDate = settledAt.length() >= 8 ? settledAt.substring(0, 8) : null;
                    txService.txSettlementBok(pi, bokReferenceNo, settledAt, settlementDate);
                    metrics.paymentCompleted(pi.getRequestedAt());
                    log.info("[BOK] SETTLEMENT_COMPLETED 처리완료. piId={} CLEARING→COMPLETED", piId);
                } else {
                    String rejectMessage = payload.path("rejectMessage").asText();
                    log.error("[BOK-F7] 정산실패 통보 수신 → 자동보상. bokReferenceNo={} responseCode={}",
                            bokReferenceNo, responseCode);
                    metrics.compensation("F7_BOK");
                    orchestrator.processBokSettlementFailure(bokReferenceNo, responseCode, rejectMessage);
                }
                break;
            }
            case "SETTLEMENT_REJECT": {
                String bokReferenceNo = payload.path("bokReferenceNo").asText();
                String responseCode   = payload.path("responseCode").asText();
                String rejectMessage  = payload.path("rejectMessage").asText();
                String rejectedAt     = payload.path("rejectedAt").asText();

                BokSettlementTransaction bst = txService.selectByBokReferenceNo(bokReferenceNo);
                if (bst == null) {
                    log.error("[BOK] SETTLEMENT_REJECT BST 없음, skip. bokReferenceNo={}", bokReferenceNo);
                    break;
                }
                String piId = bst.getOurPaymentInstructionId();
                PaymentInstruction pi = txService.selectById(piId);
                if (pi == null) {
                    log.error("[BOK] SETTLEMENT_REJECT PI 없음, skip. bokReferenceNo={} piId={}",
                            bokReferenceNo, piId);
                    break;
                }

                // 멱등가드는 orchestrator.processBokReject 내부 (FAILED skip / CLEARING|REVERSING 분기)
                orchestrator.processBokReject(pi, bokReferenceNo, responseCode, rejectMessage, rejectedAt);
                metrics.paymentFailed();
                metrics.compensation("F3_BOK");
                log.info("[BOK] SETTLEMENT_REJECT 처리완료. piId={} CLEARING→REVERSING→FAILED", piId);
                break;
            }
            default:
                log.warn("[BOK] unknown messageType: messageType={} key={}", messageType, record.key());
        }

        log.info("BOK response received: key={} messageType={} partition={} offset={}",
                record.key(), messageType, record.partition(), record.offset());

        metrics.consumed("bok.network.response");
        // ★ DB COMMIT 후 ack. 예외 시 미호출 → DLQ 라우팅(BokKafkaConfig 3회 재시도)
        ack.acknowledge();
    }
}
