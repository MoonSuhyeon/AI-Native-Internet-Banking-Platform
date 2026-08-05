package com.bank.payment.common.exception;

/**
 * B-4 입금 API 실패 — 보상 트랜잭션 필요 신호 (P-002).
 * B-3 출금이 이미 성공한 상태에서 B-4가 실패했으므로 REVERSING 진입 후 B-5 출금취소 필수.
 * PaymentValidationException(4xx 비즈니스 거절)과 구분: 이 예외는 외부 자금변동 발생 후 실패.
 */
public class DepositInboundFailureException extends RuntimeException {

    private final String depositResponseCode;

    public DepositInboundFailureException(String depositResponseCode, String message) {
        super(message);
        this.depositResponseCode = depositResponseCode;
    }

    public String getDepositResponseCode() {
        return depositResponseCode;
    }
}
