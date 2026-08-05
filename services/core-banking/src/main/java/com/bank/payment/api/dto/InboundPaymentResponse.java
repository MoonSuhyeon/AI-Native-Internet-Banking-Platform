package com.bank.payment.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record InboundPaymentResponse(
        String paymentInstructionId,
        String transactionNo,
        BigDecimal transferAmount,
        String status,
        LocalDateTime requestedAt,
        LocalDateTime completedAt,
        String senderAccountNoSnap,
        String receiverPassbookSenderDisplay,
        String receiverMemo
) {}
