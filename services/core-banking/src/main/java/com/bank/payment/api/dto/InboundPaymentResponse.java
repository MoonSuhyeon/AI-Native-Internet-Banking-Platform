package com.bank.payment.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record InboundPaymentResponse(
        String paymentInstructionId,
        String transactionNo,
        BigDecimal transferAmount,
        String status,
        OffsetDateTime requestedAt,
        OffsetDateTime completedAt,
        String senderAccountNoSnap,
        String receiverPassbookSenderDisplay,
        String receiverMemo
) {}
