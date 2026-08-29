package com.bank.loan.payment.client.dto;

import java.math.BigDecimal;

/**
 * core-banking 내부 결제 요청.
 *
 * <p>{@code operation} 이 맨 앞인 이유는 이것이 인가의 입력이기 때문이다. 나머지는
 * 이체 지시고, 이 값 하나가 "여신이 이 거래를 할 권한이 있는가" 를 가른다.
 *
 * <p>거짓 선언은 계좌 정책이 잡는다 — {@code LOAN_DISBURSE} 는 집행계좌에,
 * {@code LOAN_REPAY} 는 수납계좌에 묶여 있어 바꿔 부르면 거절된다.
 */
public record PaymentRequest(
        String operation,
        String senderAccountId,
        String receiverBankCode,
        String receiverAccountNo,
        String receiverHolderName,
        BigDecimal transferAmount,
        String senderMemo,
        String receiverMemo,
        String channel,
        String receiverPassbookSenderDisplay
) {}
