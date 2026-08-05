package com.bank.payment.domain.service;

import java.math.BigDecimal;

/**
 * 결제 처리 명령. api 입력(PaymentRequest + 헤더 신원/멱등키)을 도메인 입력으로 번역.
 * Controller가 조립. Orchestrator는 이것만 받음 (api/dto 의존 없음).
 */
public record PaymentCommand(
        // 이체 지시 (PaymentRequest에서)
        String senderAccountId,
        String receiverBankCode,
        String receiverAccountNo,
        String receiverHolderName,
        BigDecimal transferAmount,
        String receiverMemo,
        String senderMemo,
        String channel,
        String receiverPassbookSenderDisplay,
        // 신원 (헤더에서)
        String userId,
        String authTokenId,
        // 멱등키 (헤더에서)
        String idempotencyKey
) {}
