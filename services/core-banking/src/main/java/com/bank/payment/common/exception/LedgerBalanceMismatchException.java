package com.bank.payment.common.exception;

/**
 * 회계 무결성 위반: 분개 차변 합 ≠ 대변 합 (P-014).
 * 절대 발생하면 안 되는 시스템 무결성 오류 (5xx). PaymentValidationException(4xx 사용자검증)과 구분.
 * @RestControllerAdvice가 이것만 별도 회계경보로 매핑 (F1+). txStep4 트랜잭션 안에서 던지면 자동 롤백.
 */
public class LedgerBalanceMismatchException extends RuntimeException {

    public LedgerBalanceMismatchException(String message) {
        super(message);
    }
}
