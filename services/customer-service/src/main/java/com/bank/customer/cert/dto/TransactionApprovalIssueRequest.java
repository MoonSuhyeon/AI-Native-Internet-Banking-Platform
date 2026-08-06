package com.bank.customer.cert.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 거래 승인 요청. 인증 정보와 <b>승인 대상 거래</b>를 함께 받는다.
 *
 * <p>거래 내용을 받는 이유는 발급되는 토큰을 그 거래에 묶기 위해서다. 이것이 없으면
 * 토큰은 "이 사람이 인증했다"까지만 뜻하게 되고, 소액 이체로 받은 토큰으로 고액을
 * 보낼 수 있다.
 */
public record TransactionApprovalIssueRequest(
        @NotNull  Long       fromAccountId,
        @NotBlank String     toAccountNo,
        @NotNull @Positive BigDecimal amount,
        @NotBlank String     certSerialNumber,
        @NotBlank String     pin
) {}
