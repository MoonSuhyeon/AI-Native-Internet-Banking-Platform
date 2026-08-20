package com.bank.deposit.dto.response;

import java.util.List;

/**
 * 이자소득 원천징수영수증 — 화면의 "연말정산증명서".
 *
 * <p>은행이 연말정산에 내주는 서류는 그 해에 받은 이자와 원천징수한 세액이다.
 * 계좌마다 따로 주지 않고 <b>한 해치를 합쳐</b> 준다 — 연말정산에 적는 값이
 * 합계이기 때문이다. 계좌별 내역도 함께 담아 합계가 어디서 왔는지 볼 수 있게 한다.
 *
 * @param taxYear         귀속연도
 * @param totalTaxAmount  소득세와 지방소득세의 합. 둘을 따로도 준다 — 원천징수영수증이
 *                        둘을 나눠 적기 때문이다.
 */
public record InterestIncomeStatementResponse(
        int taxYear,
        long totalInterestBeforeTax,
        long totalIncomeTax,
        long totalLocalIncomeTax,
        long totalTaxAmount,
        long totalInterestAfterTax,
        List<AccountLine> accounts
) {

    public record AccountLine(
            Long accountId,
            String accountNumber,
            String accountAlias,
            long interestBeforeTax,
            long incomeTax,
            long localIncomeTax,
            long interestAfterTax,
            int paymentCount
    ) {
    }

    public static InterestIncomeStatementResponse of(int taxYear, List<AccountLine> lines) {
        return new InterestIncomeStatementResponse(
                taxYear,
                lines.stream().mapToLong(AccountLine::interestBeforeTax).sum(),
                lines.stream().mapToLong(AccountLine::incomeTax).sum(),
                lines.stream().mapToLong(AccountLine::localIncomeTax).sum(),
                lines.stream().mapToLong(l -> l.incomeTax() + l.localIncomeTax()).sum(),
                lines.stream().mapToLong(AccountLine::interestAfterTax).sum(),
                lines);
    }
}
