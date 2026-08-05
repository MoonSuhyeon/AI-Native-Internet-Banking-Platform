package com.bank.payment.inbound.kafka;

import com.bank.payment.common.BankCodeMapper;
import com.bank.payment.config.PaymentMetrics;
import com.bank.payment.domain.IdempotencyKey;
import com.bank.payment.domain.mapper.IdempotencyKeyMapper;
import com.bank.payment.domain.service.InboundPaymentCommand;
import com.bank.payment.domain.service.InboundPaymentOrchestrator;
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

import java.math.BigDecimal;

@Slf4j
@Component
@RequiredArgsConstructor
public class KftcNetworkRequestConsumer {

    private final ObjectMapper objectMapper;
    private final PaymentTransactionService txService;
    private final InboundPaymentOrchestrator inboundOrchestrator;
    private final IdempotencyKeyMapper idempotencyKeyMapper;
    private final PaymentMetrics metrics;

    @Value("${payment.bank-code:A}")
    private String bankCode;

    @KafkaListener(
            topics = "kftc.network.request",
            containerFactory = "kftcListenerContainerFactory",
            groupId = "${payment.kafka.kftc.consumer-group}"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        JsonNode payload = objectMapper.readTree(record.value());
        String messageType = payload.path("messageType").asText();

        if (!"PAYMENT_REQUEST".equals(messageType)) {
            log.warn("[IN] 알 수 없는 messageType skip: messageType={} key={}", messageType, record.key());
            ack.acknowledge();
            return;
        }

        String myCode = BankCodeMapper.toNumeric(bankCode);
        String senderBankCode = payload.path("sender").path("bankCode").asText();
        String receiverBankCode = payload.path("receiver").path("bankCode").asText();

        // P-029 주방어: receiver가 자행이 아니면 스킵
        if (!myCode.equals(receiverBankCode)) {
            log.debug("[IN] P-029 skip: not my bank. receiverBankCode={} myCode={}", receiverBankCode, myCode);
            ack.acknowledge();
            return;
        }
        // P-029 보조방어: sender가 자행이면 자신이 보낸 OUT 메시지 — 스킵
        if (myCode.equals(senderBankCode)) {
            log.debug("[IN] P-029 skip: self-originated. senderBankCode={}", senderBankCode);
            ack.acknowledge();
            return;
        }

        InboundPaymentCommand command = parsePayload(payload, messageType, senderBankCode, receiverBankCode);

        // 멱등 선조회: KFTC at-least-once 재전송은 정상 상황이므로 DLQ 대신 조용히 skip
        IdempotencyKey existing = idempotencyKeyMapper.selectByKey(command.clearingNo());
        if (existing != null) {
            log.info("[IN] 멱등 재수신 skip: 이미 처리된 clearingNo={}", command.clearingNo());
            metrics.idempotencyDuplicate();
            ack.acknowledge();
            return;
        }

        String piId = txService.txInboundReceive(command);
        inboundOrchestrator.processInbound(piId, command);

        log.info("[IN] PAYMENT_REQUEST 수신 완료: piId={} clearingNo={} key={} partition={} offset={}",
                piId, command.clearingNo(), record.key(), record.partition(), record.offset());

        metrics.consumed("kftc.network.request");
        ack.acknowledge();
    }

    private InboundPaymentCommand parsePayload(JsonNode payload, String messageType,
                                               String senderBankCode, String receiverBankCode) {
        JsonNode sender = payload.path("sender");
        JsonNode receiver = payload.path("receiver");
        BigDecimal transferAmount = new BigDecimal(payload.path("amount").asText("0"));

        return new InboundPaymentCommand(
                payload.path("clearingNo").asText(),
                payload.path("correlationId").asText(),
                messageType,
                senderBankCode,
                sender.path("accountNo").asText(),
                sender.path("realName").asText(),
                sender.path("displayName").asText(),
                receiverBankCode,
                receiver.path("accountNo").asText(),
                receiver.path("expectedHolderName").asText(),
                transferAmount,
                payload.path("currency").asText("KRW"),
                payload.path("sentAt").asText(),
                payload.path("receiverPassbookMemo").asText()
        );
    }
}
