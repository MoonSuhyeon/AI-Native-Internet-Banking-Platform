package com.bank.payment.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 예약이체 등록 요청 본문. 즉시이체 요청 필드 전체 + scheduledExecutionAt(필수) 추가.
 * 신원(userId/authTokenId)·멱등키는 헤더로 받아 Controller가 PaymentCommand로 조립.
 */
public record ScheduledPaymentRequest(
        String senderAccountId,
        String receiverBankCode,
        String receiverAccountNo,
        String receiverHolderName,
        BigDecimal transferAmount,
        String receiverMemo,
        String senderMemo,
        String channel,
        String receiverPassbookSenderDisplay,
        LocalDateTime scheduledExecutionAt
) {}
