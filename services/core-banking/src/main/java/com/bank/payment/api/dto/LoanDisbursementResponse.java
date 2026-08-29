package com.bank.payment.api.dto;

/**
 * 대출 실행 응답.
 *
 * @param journalNo    원장 분개 묶음 번호. 여신이 이것을 자기 회계번호로 저장한다 —
 *                     번호 체계가 둘이면 어느 쪽이 원장의 사실인지 알 수 없다
 * @param balanceAfter 실행 후 고객 계좌 잔액
 */
public record LoanDisbursementResponse(
        String journalNo,
        Long balanceAfter
) {}
