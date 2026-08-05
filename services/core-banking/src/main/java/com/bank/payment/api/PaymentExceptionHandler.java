package com.bank.payment.api;

import com.bank.payment.common.exception.PaymentCancelConflictException;
import com.bank.payment.common.exception.PaymentNotFoundException;
import com.bank.payment.common.exception.PaymentUnauthorizedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 취소 엔드포인트 예외 매핑.
 * PaymentNotFoundException(404), PaymentUnauthorizedException(403), PaymentCancelConflictException(409).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(PaymentNotFoundException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(PaymentUnauthorizedException.class)
    public ResponseEntity<Map<String, String>> handleUnauthorized(PaymentUnauthorizedException e) {
        return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(PaymentCancelConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(PaymentCancelConflictException e) {
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    }
}
