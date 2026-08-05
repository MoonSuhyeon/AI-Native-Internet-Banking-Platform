package com.bank.payment.common.exception;

public class PaymentCancelConflictException extends RuntimeException {

    private final String status;

    public PaymentCancelConflictException(String status) {
        super("취소 불가 상태: " + status);
        this.status = status;
    }

    public String getStatus() {
        return status;
    }
}
