package com.bank.payment.api.dto;


/**
 * 결제(이체) 요청 본문. 순수 이체 지시만 담음.
 * 신원(userId/authTokenId)·멱등키는 헤더로 받아 Controller가 PaymentCommand로 조립.
 */
public record PaymentRequest(
        String senderAccountId,
        String receiverBankCode,
        String receiverAccountNo,
        String receiverHolderName,
        Long transferAmount,
        String receiverMemo,
        String senderMemo,
        String channel,
        String receiverPassbookSenderDisplay
) {}
