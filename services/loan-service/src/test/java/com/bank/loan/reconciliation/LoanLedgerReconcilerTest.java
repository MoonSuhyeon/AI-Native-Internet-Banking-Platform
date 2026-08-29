package com.bank.loan.reconciliation;

import com.bank.loan.reconciliation.LoanLedgerReconciler.LoanReconciliationInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대사 판정을 고정한다.
 *
 * <p>대사 로직이 틀리면 조용히 "이상 없음" 을 보고한다. 그것은 대사가 없는 것보다
 * 나쁘다 — 없으면 없는 줄 알지만, 틀리면 있다고 믿기 때문이다.
 */
class LoanLedgerReconcilerTest {

    private static LoanReconciliationInput input(long sdA, long sdC, long srA, long srC,
                                                 long ldA, long ldC, long lrA, long lrC) {
        return new LoanReconciliationInput("20260829", sdA, sdC, srA, srC, ldA, ldC, lrA, lrC);
    }

    @Test
    @DisplayName("양쪽이 같으면 불일치가 없다")
    void matches() {
        List<String> breaks = LoanLedgerReconciler.reconcile(
                input(2_000_000, 1, 500_000, 2,
                      2_000_000, 1, 500_000, 2));

        assertThat(breaks).isEmpty();
    }

    @Test
    @DisplayName("거래가 없던 날도 일치다")
    void matchesOnEmptyDay() {
        List<String> breaks = LoanLedgerReconciler.reconcile(input(0, 0, 0, 0, 0, 0, 0, 0));

        assertThat(breaks).isEmpty();
    }

    @Test
    @DisplayName("실행액이 어긋나면 어느 쪽이 무엇이라 했는지 남긴다")
    void reportsDisbursedAmountBreak() {
        List<String> breaks = LoanLedgerReconciler.reconcile(
                input(2_000_000, 1, 0, 0,
                      1_000_000, 1, 0, 0));

        assertThat(breaks).singleElement().asString()
                .contains("실행액")
                .contains("보조부=2000000")
                .contains("원장=1000000")
                .contains("차이=1000000");
    }

    @Test
    @DisplayName("합계가 맞아도 건수가 다르면 잡는다")
    void catchesCountMismatchWhenAmountsAgree() {
        // 같은 날 두 건이 서로 반대로 어긋나면 합계는 맞는다. 그때 건수가 다르다는
        // 것은 원장과 보조부가 다른 사건을 세고 있다는 뜻이라, 합계가 맞는 것이
        // 오히려 위험하다. 금액만 보는 대사는 이것을 통과시킨다.
        List<String> breaks = LoanLedgerReconciler.reconcile(
                input(2_000_000, 2, 0, 0,
                      2_000_000, 1, 0, 0));

        assertThat(breaks).singleElement().asString()
                .contains("실행건수")
                .contains("보조부=2")
                .contains("원장=1");
    }

    @Test
    @DisplayName("여러 항목이 어긋나면 모두 남긴다")
    void reportsEveryBreak() {
        List<String> breaks = LoanLedgerReconciler.reconcile(
                input(2_000_000, 2, 500_000, 3,
                      1_000_000, 1, 400_000, 2));

        // 하나만 보고하고 멈추면 나머지는 다음 날에야 드러난다.
        assertThat(breaks).hasSize(4);
        assertThat(breaks).anySatisfy(b -> assertThat(b).contains("실행액"));
        assertThat(breaks).anySatisfy(b -> assertThat(b).contains("실행건수"));
        assertThat(breaks).anySatisfy(b -> assertThat(b).contains("상환액"));
        assertThat(breaks).anySatisfy(b -> assertThat(b).contains("상환건수"));
    }

    @Test
    @DisplayName("원장이 더 많은 경우도 잡는다 — 차이가 음수로 남는다")
    void catchesLedgerAhead() {
        List<String> breaks = LoanLedgerReconciler.reconcile(
                input(0, 0, 0, 0,
                      3_000_000, 1, 0, 0));

        // 보조부에 없는 실행이 원장에 있다. 보조부가 더 센 것과는 다른 사건이라
        // 부호로 구분되어야 한다.
        assertThat(breaks).anySatisfy(b -> assertThat(b).contains("차이=-3000000"));
    }
}
