package com.bank.deposit.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

@Slf4j
/**
 * deposit 도메인 전용 예외 처리.
 *
 * <p>병합 전에는 서비스마다 GlobalExceptionHandler 하나씩이라 이름이 같아도 무방했다.
 * 한 프로세스에 둘이 들어오면서 빈 이름이 충돌했고, 그보다 중요한 문제로
 * 수신계의 Exception 포괄 핸들러가 결제계의 미처리 예외까지 가로채 응답 형식을
 * 바꿔버린다. 그래서 각자 자기 패키지의 컨트롤러에만 적용되도록 범위를 가둔다.
 */
@RestControllerAdvice(basePackages = "com.bank.deposit")
public class DepositExceptionHandler {

    /**
     * 이상거래로 세운 거래 — 근거와 행동요령을 함께 내보낸다.
     *
     * <p>{@link BusinessException} 핸들러보다 <b>먼저</b> 잡혀야 한다. 스프링은 더
     * 구체적인 예외 타입의 핸들러를 고르므로 순서가 아니라 타입으로 결정되지만,
     * 이 메서드를 지우면 안내가 조용히 사라지고 예전의 한 줄로 돌아간다.
     */
    @ExceptionHandler(RiskGuidedException.class)
    public ResponseEntity<ErrorResponse> handleRiskGuided(RiskGuidedException e) {
        log.warn("이상거래로 거래 중단: {} guidance={}", e.getMessage(), e.getGuidance() != null);
        return ResponseEntity.status(e.getErrorCode().getStatus())
                .body(ErrorResponse.of(e.getErrorCode(), e.getMessage(), e.getGuidance()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        log.warn("BusinessException: {}", e.getMessage());
        return ResponseEntity.status(e.getErrorCode().getStatus())
                .body(ErrorResponse.of(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<String> errors = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .toList();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.INVALID_STATUS, errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        List<String> errors = e.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.INVALID_STATUS, errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.INVALID_STATUS, "요청 본문을 읽을 수 없습니다: " + e.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.INVALID_STATUS,
                        "필수 요청 파라미터가 누락되었습니다: " + e.getParameterName()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(ErrorCode.NOT_FOUND.getStatus())
                .body(ErrorResponse.of(ErrorCode.NOT_FOUND, "요청한 경로를 찾을 수 없습니다."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.internalServerError()
                .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR));
    }
}
