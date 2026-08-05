package com.bank.payment.outbound.feign.dto;

import java.math.BigDecimal;

// deposit 실제 WithdrawRequest (deposit-service/.../dto/request/WithdrawRequest.java)
// accountId(Long @NotNull), amount(BigDecimal @NotNull), channelType(선택), transactionMemo(선택)
// counterparty/currency/referenceNo/transactionType 미지원 → transactionMemo에 piId 박제
public record WithdrawRequest(
        Long accountId,
        BigDecimal amount,
        String channelType,     // deposit TransactionChannel: BRANCH/ATM/INTERNET/MOBILE/SYSTEM
        String transactionMemo  // piId | userMemo — referenceNo 대체, 추적성 유지
) {}
