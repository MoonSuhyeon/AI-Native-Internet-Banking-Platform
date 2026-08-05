package com.bank.payment.domain.service;

import java.math.BigDecimal;

/**
 * KFTC kftc.network.request PAYMENT_REQUEST 수신 명령.
 * KftcNetworkRequestConsumer에서 파싱해 txService.txInboundReceive()로 전달.
 */
public record InboundPaymentCommand(
        String clearingNo,
        String correlationId,
        String messageType,
        String senderBankCode,
        String senderAccountNo,
        String senderRealName,
        String senderDisplayName,
        String receiverBankCode,
        String receiverAccountNo,
        String receiverExpectedHolderName,
        BigDecimal transferAmount,
        String currency,
        String sentAt,
        String receiverPassbookMemo
) {}
