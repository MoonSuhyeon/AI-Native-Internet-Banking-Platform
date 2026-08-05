package com.bank.deposit.dto.interservice;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

public record ApiResponse<T>(
        String code,
        String message,
        String timestamp,
        T data
) {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("DEP-0000", "success", OffsetDateTime.now().format(FMT), data);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(code, message, OffsetDateTime.now().format(FMT), null);
    }
}
