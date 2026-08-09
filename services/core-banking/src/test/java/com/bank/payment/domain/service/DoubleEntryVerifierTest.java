package com.bank.payment.domain.service;

import com.bank.payment.common.exception.LedgerBalanceMismatchException;
import com.bank.payment.domain.Ledger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 복식부기 검증이 실제로 무언가를 잡는지 확인한다.
 *
 * <p><b>이 파일이 있는 이유.</b> 검증은 원래도 있었다. 다만 방금 만든 두 객체를
 * 견주는 형태였고 둘 다 같은 지역변수에서 나왔으므로 참이 될 수 없었다. 즉
 * <b>있는데 아무것도 안 하는 검증</b>이었고, 그 상태에서는 원장이 깨져도 조용하다.
 *
 * <p>그래서 여기서 보는 것은 "균형이면 통과한다" 가 아니다. 그건 예전 코드도 했다.
 * <b>깨졌을 때 실제로 걸리는가</b> 를 경우별로 본다 — 다리 누락, 차대구분 오류,
 * 금액 불일치, 그리고 아무것도 안 남은 경우.
 */
class DoubleEntryVerifierTest {

    private static final String PI = "PI-0001";
    private static final String JN1 = "JN-0001";
    private static final String JN2 = "JN-0002";

    private static Ledger leg(String journalNo, String drCr, long amount) {
        return Ledger.builder()
                .ledgerId("L-" + journalNo + "-" + drCr + "-" + amount)
                .paymentInstructionId(PI)
                .journalNo(journalNo)
                .debitCredit(drCr)
                .amount(amount)
                .build();
    }

    // ── 정상 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("차변=대변이면 통과한다")
    void balancedPasses() {
        assertThatCode(() -> DoubleEntryVerifier.verify(
                List.of(leg(JN1, "DEBIT", 10_000L), leg(JN1, "CREDIT", 10_000L)), PI))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("묶음이 둘이어도 각각 균형이면 통과한다 — 타행이체(본금 JN-01 + 수수료 JN-02)")
    void twoBalancedJournalsPass() {
        assertThatCode(() -> DoubleEntryVerifier.verify(List.of(
                leg(JN1, "DEBIT", 10_000L), leg(JN1, "CREDIT", 10_000L),
                leg(JN2, "DEBIT", 500L), leg(JN2, "CREDIT", 500L)), PI))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("역분개가 원분개와 같은 묶음에 들어와도 합계는 균형이다")
    void reversalInSameJournalStaysBalanced() {
        // 역분개는 원분개와 같은 journal_no 를 쓴다(의도된 설계).
        // 원 차변+역 차변 = 원 대변+역 대변 이 되어야 한다.
        assertThatCode(() -> DoubleEntryVerifier.verify(List.of(
                leg(JN1, "DEBIT", 10_000L), leg(JN1, "CREDIT", 10_000L),   // 원분개
                leg(JN1, "CREDIT", 10_000L), leg(JN1, "DEBIT", 10_000L)),  // 역분개
                PI))
                .doesNotThrowAnyException();
    }

    // ── 예전 검증이 놓치던 것들 ──────────────────────────────────────────────

    @Test
    @DisplayName("다리 하나가 저장되지 않으면 걸린다 — 예전 검증이 못 잡던 대표 사고")
    void missingLegIsCaught() {
        // INSERT 가 조용히 빠진 상황. 코드베이스에 이걸 재현하는 시뮬레이터가 이미
        // 있는데도, 예전 검증은 메모리 객체만 봐서 통과시켰다.
        assertThatThrownBy(() -> DoubleEntryVerifier.verify(
                List.of(leg(JN1, "DEBIT", 10_000L)), PI))
                .isInstanceOf(LedgerBalanceMismatchException.class)
                .hasMessageContaining("DEBIT 10000")
                .hasMessageContaining("CREDIT 0");
    }

    @Test
    @DisplayName("두 다리가 다 차변이면 걸린다 — 금액만 보면 같아서 통과하던 경우")
    void bothLegsDebitIsCaught() {
        assertThatThrownBy(() -> DoubleEntryVerifier.verify(
                List.of(leg(JN1, "DEBIT", 10_000L), leg(JN1, "DEBIT", 10_000L)), PI))
                .isInstanceOf(LedgerBalanceMismatchException.class)
                .hasMessageContaining("DEBIT 20000");
    }

    @Test
    @DisplayName("저장된 금액이 어긋나면 걸린다 — 매퍼가 엉뚱한 값을 쓴 경우")
    void amountMismatchIsCaught() {
        assertThatThrownBy(() -> DoubleEntryVerifier.verify(
                List.of(leg(JN1, "DEBIT", 10_000L), leg(JN1, "CREDIT", 9_000L)), PI))
                .isInstanceOf(LedgerBalanceMismatchException.class)
                .hasMessageContaining(JN1);
    }

    @Test
    @DisplayName("한 묶음만 깨져도 걸린다 — 수수료 묶음이 어긋난 경우")
    void oneBrokenJournalAmongManyIsCaught() {
        assertThatThrownBy(() -> DoubleEntryVerifier.verify(List.of(
                leg(JN1, "DEBIT", 10_000L), leg(JN1, "CREDIT", 10_000L),
                leg(JN2, "DEBIT", 500L), leg(JN2, "CREDIT", 300L)), PI))
                .isInstanceOf(LedgerBalanceMismatchException.class)
                .hasMessageContaining(JN2);
    }

    @Test
    @DisplayName("차/대 구분이 비면 걸린다 — 어느 쪽에도 안 더해져 0=0 으로 보이는 것을 막는다")
    void unknownIndicatorIsCaught() {
        assertThatThrownBy(() -> DoubleEntryVerifier.verify(
                List.of(leg(JN1, null, 10_000L), leg(JN1, "Debit", 10_000L)), PI))
                .isInstanceOf(LedgerBalanceMismatchException.class)
                .hasMessageContaining("DEBIT·CREDIT");
    }

    @Test
    @DisplayName("분개가 한 건도 없으면 걸린다 — 빈 목록을 균형으로 보면 전부 실패한 사고가 지나간다")
    void emptyIsCaught() {
        assertThatThrownBy(() -> DoubleEntryVerifier.verify(List.of(), PI))
                .isInstanceOf(LedgerBalanceMismatchException.class)
                .hasMessageContaining("한 건도 없다");

        assertThatThrownBy(() -> DoubleEntryVerifier.verify(null, PI))
                .isInstanceOf(LedgerBalanceMismatchException.class);
    }

    @Test
    @DisplayName("양쪽 다 0 이면 걸린다 — 산술적으로는 균형이지만 기록이 없는 것이다")
    void zeroAmountsAreCaught() {
        assertThatThrownBy(() -> DoubleEntryVerifier.verify(
                List.of(leg(JN1, "DEBIT", 0L), leg(JN1, "CREDIT", 0L)), PI))
                .isInstanceOf(LedgerBalanceMismatchException.class)
                .hasMessageContaining("0 이다");
    }

    // ── 진단 가능성 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("메시지에 어느 묶음이 깨졌는지 남는다 — 없으면 결제 전체를 뒤져야 한다")
    void messageIdentifiesTheBrokenJournal() {
        assertThatThrownBy(() -> DoubleEntryVerifier.verify(List.of(
                leg(JN1, "DEBIT", 10_000L), leg(JN1, "CREDIT", 10_000L),
                leg(JN2, "DEBIT", 500L), leg(JN2, "CREDIT", 300L)), PI))
                .satisfies(e -> assertThat(e.getMessage())
                        .contains(JN2)
                        .contains("500")
                        .contains("300")
                        .contains(PI));
    }
}
