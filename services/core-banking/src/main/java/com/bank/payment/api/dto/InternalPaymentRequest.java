package com.bank.payment.api.dto;

/**
 * 시스템이 개시하는 결제 요청.
 *
 * <p>고객 요청({@link PaymentRequest})과 필드는 거의 같지만 <b>계약이 다르다.</b>
 * 여기에는 {@code operation} 이 있고 고객 인증 관련 값이 없다. 근거는
 * {@code docs/decisions/transaction-initiator-auth-model.md}.
 *
 * <p><b>왜 operation 을 본문에 두는가.</b> 이것은 업무 의도이므로 요청 계약의 일부다.
 * 그리고 거짓 선언은 계좌 정책이 잡는다 — {@code LOAN_DISBURSE} 는 집행계좌에,
 * 상환은 수납계좌에 묶여 있어 바꿔 부르면 {@code ACCOUNT_NOT_ALLOWED} 로 거절된다.
 * 즉 선언만으로 권한이 넓어지지 않는다.
 *
 * @param operation 수행하려는 작업 ({@code LOAN_DISBURSE} 등)
 */
public record InternalPaymentRequest(
        String operation,
        String senderAccountId,
        String receiverBankCode,
        String receiverAccountNo,
        String receiverHolderName,
        Long transferAmount,
        String senderMemo,
        String receiverMemo,
        String channel,
        String receiverPassbookSenderDisplay
) {
}
