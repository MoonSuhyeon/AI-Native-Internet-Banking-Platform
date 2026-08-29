package com.bank.loan.payment.client.dto;

import java.math.BigDecimal;

/**
 * 대출 실행 요청.
 *
 * <p>송신계좌가 없다. 대출 실행은 집행계좌에서 돈을 빼오는 일이 아니라 대출채권과
 * 고객 예금이 동시에 생기는 회계 사건이다.
 */
public record LoanDisbursementRequest(
        String accountNo,
        BigDecimal amount,
        String memo
) {}
