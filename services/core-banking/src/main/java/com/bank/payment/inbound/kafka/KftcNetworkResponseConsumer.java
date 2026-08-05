package com.bank.payment.inbound.kafka;

import com.bank.payment.config.PaymentMetrics;
import com.bank.payment.domain.KftcClearingTransaction;
import com.bank.payment.domain.PaymentInstruction;
import com.bank.payment.domain.service.PaymentOrchestrator;
import com.bank.payment.domain.service.PaymentTransactionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KftcNetworkResponseConsumer {

    private final ObjectMapper objectMapper;
    private final PaymentTransactionService txService;
    private final PaymentOrchestrator orchestrator;
    private final PaymentMetrics metrics;

    @Value("${payment.bank-code:A}")
    private String bankCode;

    @KafkaListener(
            topics = "kftc.network.response",
            containerFactory = "kftcListenerContainerFactory",
            groupId = "${payment.kafka.kftc.consumer-group}"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        JsonNode payload = objectMapper.readTree(record.value());
        String messageType = payload.path("messageType").asText();

        // TODO P-029 self-listening 방지: senderBankCode가 자행 bankCode와 같으면 ack+skip
        // String senderBankCode = payload.path("senderBankCode").asText();
        // if (bankCode.equals(senderBankCode)) {
        //     log.debug("P-029 self-listening skip: senderBankCode={}", senderBankCode);
        //     ack.acknowledge();
        //     return;
        // }

        switch (messageType) {
            case "REJECT":
            case "PAYMENT_REJECT": {
                String clearingNo = payload.path("clearingNo").asText();
                String responseCode = payload.path("responseCode").asText();
                String rejectMessage = payload.path("rejectMessage").asText();
                String rejectedAt = payload.path("rejectedAt").asText();

                KftcClearingTransaction rejectCt = txService.selectByClearingNo(clearingNo);
                if (rejectCt == null) {
                    log.error("[KFTC] REJECT CT 없음, skip. clearingNo={}", clearingNo);
                    break;
                }
                String rejectPiId = rejectCt.getOurPaymentInstructionId();
                PaymentInstruction rejectPi = txService.selectById(rejectPiId);
                if (rejectPi == null) {
                    log.error("[KFTC] REJECT PI 없음, skip. clearingNo={} piId={}", clearingNo, rejectPiId);
                    break;
                }

                // 결정 (f) 멱등 가드
                String rejectStatus = rejectPi.getStatus();
                if ("FAILED".equals(rejectStatus)) {
                    log.info("[KFTC] REJECT 이미 FAILED(중복수신 skip). piId={}", rejectPiId);
                    break;
                }
                if (!"CLEARING".equals(rejectStatus) && !"REVERSING".equals(rejectStatus)) {
                    log.warn("[KFTC] REJECT 처리불가 상태, skip. piId={} status={}", rejectPiId, rejectStatus);
                    break;
                }

                orchestrator.processKftcReject(rejectPi, clearingNo, responseCode, rejectMessage, rejectedAt);
                metrics.paymentFailed();
                metrics.compensation("F2_KFTC");
                log.info("[KFTC] REJECT 처리완료. piId={} clearingNo={}", rejectPiId, clearingNo);
                break;
            }
            case "SETTLEMENT_NOTIFY": {
                String clearingNo = payload.path("clearingNo").asText();
                String responseCode = payload.path("responseCode").asText();
                KftcClearingTransaction ct = txService.selectByClearingNo(clearingNo);
                if (ct == null) {
                    log.error("[KFTC] SETTLEMENT_NOTIFY CT 없음, skip. clearingNo={}", clearingNo);
                    break;
                }
                String piId = ct.getOurPaymentInstructionId();
                PaymentInstruction pi = txService.selectById(piId);
                if (pi == null) {
                    log.error("[KFTC] SETTLEMENT_NOTIFY PI 없음, skip. clearingNo={} piId={}", clearingNo, piId);
                    break;
                }
                if ("COMPLETED".equals(pi.getStatus())) {
                    log.info("[KFTC] SETTLEMENT_NOTIFY 중복수신 skip(이미 COMPLETED). piId={}", piId);
                    break;
                }
                if (!"CLEARING".equals(pi.getStatus())) {
                    log.warn("[KFTC] SETTLEMENT_NOTIFY CLEARING 아닌 상태, skip. piId={} status={}", piId, pi.getStatus());
                    break;
                }
                if ("0000".equals(responseCode)) {
                    String settledAt = payload.path("settledAt").asText();
                    String settlementDate = settledAt.length() >= 8 ? settledAt.substring(0, 8) : null;
                    txService.txSettlement(pi, clearingNo, settledAt, settlementDate);
                    metrics.paymentCompleted(pi.getRequestedAt());
                    log.info("[KFTC] SETTLEMENT_NOTIFY 처리완료. piId={} CLEARING→COMPLETED", piId);
                } else {
                    // F7: 정산실패 통보 — 상태 가드(COMPLETED skip / !CLEARING skip) 통과(=CLEARING) 후 진입
                    String rejectMessage = payload.path("rejectMessage").asText();
                    log.error("[F7] KFTC 정산실패 통보 수신 → 자동보상. clearingNo={} responseCode={}", clearingNo, responseCode);
                    metrics.compensation("F7_KFTC");
                    orchestrator.processSettlementFailure(clearingNo, responseCode, rejectMessage);
                }
                break;
            }
            default:
                log.warn("[KFTC] unknown messageType: messageType={} key={}", messageType, record.key());
        }

        log.info("KFTC response received: key={} messageType={} partition={} offset={}",
                record.key(), messageType, record.partition(), record.offset());

        metrics.consumed("kftc.network.response");
        // ★ DB COMMIT 후 ack. 예외 시 미호출 → Kafka 재전달
        ack.acknowledge();
    }
}
