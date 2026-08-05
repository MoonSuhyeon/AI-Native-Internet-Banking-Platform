package com.bank.payment.outbound.feign.dto;

/**
 * B-5 POST /api/v1/balances/withdraw/cancel 요청 본문.
 * 합의서 v1.0 시트13.
 */
public record WithdrawCancelRequest(
        String originalDepositTransactionNo,   // 원 B-3 거래 식별자 (deposit common_transaction no)
        String accountNo,                       // 원 출금 계좌번호
        Long amount,                            // 원 출금액과 동일
        String reason,                          // PAYMENT_FAILED / OPERATOR_CANCEL / FRAUD_REPORT
        String referenceNo                      // 결제지시번호
) {}
