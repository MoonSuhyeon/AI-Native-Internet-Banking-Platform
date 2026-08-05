package com.bank.payment.api.dto;

import java.time.LocalDateTime;

/**
 * 결제 응답. 200 OK — COMPLETED(정상) 또는 FAILED(비즈니스 거절).
 * failureCategory: FAILED 시 원인 코드(INSUFFICIENT_BALANCE 등), 정상 시 null.
 */
public record PaymentResponse(
        String paymentInstructionId,
        String transactionNo,
        String status,
        LocalDateTime completedAt,
        String failureCategory
) {}
