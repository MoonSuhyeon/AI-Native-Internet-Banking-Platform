package com.bank.payment.common.exception;

/**
 * 결제 검증 실패 예외 (step2 외부검증 / step3 출금 비즈니스 거절).
 * 예: 잔액부족, 예금주불일치, 계좌상태이상 등.
 * @RestControllerAdvice가 받아 에러 응답 변환 (F1 시나리오에서 핸들러 추가).
 */
public class PaymentValidationException extends RuntimeException {

    private final String failureCategory;

    public PaymentValidationException(String failureCategory, String message) {
        super(message);
        this.failureCategory = failureCategory;
    }

    public String getFailureCategory() {
        return failureCategory;
    }
}
