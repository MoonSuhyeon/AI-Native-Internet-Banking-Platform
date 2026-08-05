package com.bank.payment.outbound.feign.dto;

import java.util.List;

// deposit-service GlobalExceptionHandler 가 반환하는 에러 응답 구조 (ErrorResponse.java 참조)
public record DepositErrorResponse(
        String code,        // ErrorCode.name() e.g. "INSUFFICIENT_BALANCE", "ACCOUNT_NOT_FOUND"
        String message,
        List<String> errors,
        String timestamp
) {}
