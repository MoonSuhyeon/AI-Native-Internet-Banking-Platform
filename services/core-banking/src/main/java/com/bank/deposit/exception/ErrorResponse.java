package com.bank.deposit.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        List<String> errors,
        OffsetDateTime timestamp
) {
    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), null, OffsetDateTime.now());
    }

    public static ErrorResponse of(ErrorCode errorCode, String detail) {
        return new ErrorResponse(errorCode.name(), detail, null, OffsetDateTime.now());
    }

    public static ErrorResponse of(ErrorCode errorCode, List<String> errors) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), errors, OffsetDateTime.now());
    }
}
