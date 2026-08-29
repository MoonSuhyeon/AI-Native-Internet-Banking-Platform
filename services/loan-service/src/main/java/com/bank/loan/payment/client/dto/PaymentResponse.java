package com.bank.loan.payment.client.dto;

public record PaymentResponse(
        String paymentInstructionId,
        String transactionNo,
        String status,
        String failureCategory,
        /**
         * 원장이 발급한 분개 묶음 번호. 자행 완결에서만 온다.
         *
         * <p>예전에는 여신이 JE-{uuid} 로 자기 회계번호를 만들어 붙였다. 원장의
         * journal_no 와 아무 관계가 없는 값이라, 회계 식별자가 둘로 갈려 어느 쪽이
         * 원장의 사실인지 알 수 없었다.
         */
        String journalNo
) {
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED    = "FAILED";
    public static final String STATUS_CLEARING  = "CLEARING";
}
