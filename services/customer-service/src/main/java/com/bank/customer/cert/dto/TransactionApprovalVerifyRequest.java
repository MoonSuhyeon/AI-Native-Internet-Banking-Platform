package com.bank.customer.cert.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * 계정계가 이체 직전에 보내는 검증 요청.
 *
 * <p>거래 내용을 다시 받는 이유는 발급 때와 같은 거래인지 대조하기 위해서다.
 * 다르면 해시가 달라 조회 자체가 되지 않는다.
 */
public record TransactionApprovalVerifyRequest(
        @NotBlank String     approvalToken,
        @NotNull  Long       fromAccountId,
        @NotBlank String     toAccountNo,
        @NotNull @Positive BigDecimal amount
) {}
