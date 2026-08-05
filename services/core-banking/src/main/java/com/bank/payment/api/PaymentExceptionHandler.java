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
/**
 * payment 도메인 전용 예외 처리.
 *
 * <p>병합 전에는 서비스마다 GlobalExceptionHandler 하나씩이라 이름이 같아도 무방했다.
 * 한 프로세스에 둘이 들어오면서 빈 이름이 충돌했고, 그보다 중요한 문제로
 * 수신계의 Exception 포괄 핸들러가 결제계의 미처리 예외까지 가로채 응답 형식을
 * 바꿔버린다. 그래서 각자 자기 패키지의 컨트롤러에만 적용되도록 범위를 가둔다.
 */
@RestControllerAdvice(basePackages = "com.bank.payment")
public class PaymentExceptionHandler {

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
