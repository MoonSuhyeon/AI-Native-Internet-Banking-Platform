package com.bank.deposit.exception;

import com.bank.deposit.security.RiskGuidance;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
/**
 * 오류 응답.
 *
 * <p>{@code guidance} 는 이상거래로 거래를 세운 경우에만 채워진다. 나머지 오류에는
 * {@code null} 이고, {@code NON_NULL} 이라 필드 자체가 나가지 않는다 — 기존 응답
 * 형태는 그대로다.
 */
public record ErrorResponse(
        String code,
        String message,
        List<String> errors,
        OffsetDateTime timestamp,
        RiskGuidance guidance
) {
    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), null, OffsetDateTime.now(), null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String detail) {
        return new ErrorResponse(errorCode.name(), detail, null, OffsetDateTime.now(), null);
    }

    public static ErrorResponse of(ErrorCode errorCode, List<String> errors) {
        return new ErrorResponse(errorCode.name(), errorCode.getMessage(), errors, OffsetDateTime.now(), null);
    }

    /** 이상거래로 세운 거래 — 왜 멈췄고 지금 무엇을 할지 함께 내보낸다. */
    public static ErrorResponse of(ErrorCode errorCode, String detail, RiskGuidance guidance) {
        return new ErrorResponse(errorCode.name(), detail, null, OffsetDateTime.now(), guidance);
    }
}
