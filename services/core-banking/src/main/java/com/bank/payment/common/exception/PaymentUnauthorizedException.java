package com.bank.payment.common.exception;

public class PaymentUnauthorizedException extends RuntimeException {

    public PaymentUnauthorizedException(String piId) {
        super("취소 권한 없음: " + piId);
    }
}
