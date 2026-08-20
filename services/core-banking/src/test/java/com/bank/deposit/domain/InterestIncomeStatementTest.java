package com.bank.deposit.domain;

import com.bank.deposit.dto.response.InterestIncomeStatementResponse;
import com.bank.deposit.dto.response.InterestIncomeStatementResponse.AccountLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이자소득 원천징수영수증 합계.
 *
 * <p><b>무엇을 지키는가.</b> 고객이 연말정산에 <b>그대로 옮겨 적는</b> 숫자다.
 * 여기서 합계가 틀리면 고객이 잘못 신고하고, 잘못된 쪽은 은행이 아니라 고객이 된다.
 */
class InterestIncomeStatementTest {

    private static AccountLine line(long before, long income, long local, long after) {
        return new AccountLine(1L, "110-1", "정기예금", before, income, local, after, 2);
    }

    @Test
    @DisplayName("계좌별 값을 모두 더한다")
    void sumsAcrossAccounts() {
        var res = InterestIncomeStatementResponse.of(2026, List.of(
                line(100_000, 14_000, 1_400, 84_600),
                line(50_000, 7_000, 700, 42_300)));

        assertThat(res.totalInterestBeforeTax()).isEqualTo(150_000);
        assertThat(res.totalIncomeTax()).isEqualTo(21_000);
        assertThat(res.totalLocalIncomeTax()).isEqualTo(2_100);
        assertThat(res.totalInterestAfterTax()).isEqualTo(126_900);
    }

    @Test
    @DisplayName("총 세액은 소득세와 지방소득세의 합이다")
    void totalTaxIncludesLocalTax() {
        // 지방소득세를 빠뜨리면 원천징수영수증의 총 세액이 실제보다 작게 나온다.
        // 고객은 그 값을 그대로 신고한다.
        var res = InterestIncomeStatementResponse.of(2026, List.of(
                line(100_000, 14_000, 1_400, 84_600)));

        assertThat(res.totalTaxAmount()).isEqualTo(15_400);
    }

    @Test
    @DisplayName("이자가 없으면 모두 0 이다")
    void emptyYearIsAllZero() {
        var res = InterestIncomeStatementResponse.of(2026, List.of());

        assertThat(res.totalInterestBeforeTax()).isZero();
        assertThat(res.totalTaxAmount()).isZero();
        assertThat(res.accounts()).isEmpty();
    }

    @Test
    @DisplayName("계좌별 내역을 함께 담는다")
    void keepsPerAccountLines() {
        // 합계만 주면 숫자가 이상할 때 어디서 왔는지 확인할 길이 없다.
        var res = InterestIncomeStatementResponse.of(2026, List.of(
                line(100_000, 14_000, 1_400, 84_600),
                line(50_000, 7_000, 700, 42_300)));

        assertThat(res.accounts()).hasSize(2);
    }
}
