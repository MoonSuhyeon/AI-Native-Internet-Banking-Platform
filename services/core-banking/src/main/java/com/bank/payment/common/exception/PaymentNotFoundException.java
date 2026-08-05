package com.bank.payment.common.exception;

public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(String piId) {
        super("결제지시 없음: " + piId);
    }
}
