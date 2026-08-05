package com.bank.payment.outbound.feign.dto;

import java.math.BigDecimal;

// deposit 실제 DepositRequest (deposit-service/.../dto/request/DepositRequest.java)
// accountId(Long @NotNull), amount(BigDecimal @NotNull), channelType/transactionMemo/depositorName(선택)
// counterparty/currency/referenceNo/transactionType 미지원 → transactionMemo에 piId 박제
public record DepositRequest(
        Long accountId,
        BigDecimal amount,
        String channelType,     // deposit TransactionChannel: BRANCH/ATM/INTERNET/MOBILE/SYSTEM
        String transactionMemo, // piId | userMemo — referenceNo 대체, 추적성 유지
        String depositorName    // 선택: 통장에 표시될 입금인명 (송신자 실명 or passbookSenderDisplay)
) {}
