package com.bank.payment.api.dto;

/**
 * 대출 실행 요청.
 *
 * <p>송신계좌가 없다. 대출 실행은 어딘가에서 돈을 빼오는 일이 아니라 대출채권과
 * 고객 예금이 동시에 생기는 회계 사건이기 때문이다. 결제 요청과 필드가 다른 것은
 * 성격이 다르기 때문이지 생략한 것이 아니다.
 *
 * <p>{@code operation} 도 없다. 이 경로로 들어오는 것은 대출 실행 하나뿐이라
 * 호출자가 무엇을 하려는지 선언할 여지가 없다 — 결제처럼 여러 작업이 한 문을
 * 나눠 쓰지 않는다.
 *
 * @param accountNo 자금이 들어갈 고객 계좌번호
 * @param amount    실행 금액
 * @param memo      적요. 원장에 그대로 남는다
 */
public record LoanDisbursementRequest(
        String accountNo,
        Long amount,
        String memo
) {}
