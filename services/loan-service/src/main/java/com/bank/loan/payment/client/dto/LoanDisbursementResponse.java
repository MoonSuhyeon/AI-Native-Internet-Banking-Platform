package com.bank.loan.payment.client.dto;

/**
 * 대출 실행 응답.
 *
 * @param journalNo    원장이 발급한 분개 묶음 번호. 여신은 이것을 그대로 저장한다
 * @param balanceAfter 실행 후 고객 계좌 잔액
 */
public record LoanDisbursementResponse(
        String journalNo,
        Long balanceAfter
) {}
