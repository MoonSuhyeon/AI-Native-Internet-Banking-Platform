package com.bank.payment.outbound.ledger.dto;


// deposit 실제 WithdrawRequest (deposit-service/.../dto/request/WithdrawRequest.java)
// accountId(Long @NotNull), amount(Long @NotNull), channelType(선택), transactionMemo(선택)
// counterparty/currency/referenceNo/transactionType 미지원 → transactionMemo에 piId 박제
public record WithdrawRequest(
        Long accountId,
        Long amount,
        String channelType,     // deposit TransactionChannel: BRANCH/ATM/INTERNET/MOBILE/SYSTEM
        String transactionMemo  // piId | userMemo — referenceNo 대체, 추적성 유지
) {}
