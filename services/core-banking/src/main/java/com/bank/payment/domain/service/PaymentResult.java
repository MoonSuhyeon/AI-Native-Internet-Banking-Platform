package com.bank.payment.domain.service;

import java.time.LocalDateTime;

/**
 * 결제 처리 결과 (도메인 출력). Controller가 PaymentResponse로 매핑.
 * domain이 api/dto를 모르게 하는 출력 경계 객체.
 * failureCategory: FAILED 시 원인 코드, 정상 완결 시 null.
 */
public record PaymentResult(
        String paymentInstructionId,
        String transactionNo,
        String status,
        String failureCategory,
        LocalDateTime completedAt
) {}
