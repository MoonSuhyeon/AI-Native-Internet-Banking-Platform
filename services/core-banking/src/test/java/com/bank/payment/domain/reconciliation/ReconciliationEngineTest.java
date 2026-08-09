package com.bank.payment.domain.reconciliation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대사가 실제로 어긋난 것을 잡는지 확인한다.
 *
 * <p>대사 코드에서 가장 나쁜 실패는 예외가 아니라 <b>조용한 "이상 없음"</b> 이다.
 * 아무것도 안 잡는 대사는 없는 것보다 나쁘다 — 확인했다고 믿게 만들기 때문이다.
 * 그래서 여기서는 "정상이면 통과한다" 보다 <b>깨졌을 때 걸리는가</b> 를 경우별로 본다.
 */
class ReconciliationEngineTest {

    private static final String PI = "PI-0001";

    private static ClearingRecords.Internal in(String pi, long net) {
        return new ClearingRecords.Internal(pi, net);
    }

    private static ClearingRecords.External ex(String pi, long amount, String status) {
        return new ClearingRecords.External(pi, amount, status);
    }

    private static List<ReconciliationBreak> run(
            List<ClearingRecords.Internal> internal,
            List<ClearingRecords.External> external) {
        return ReconciliationEngine.reconcile(internal, external, true);
    }

    // ── 일치 ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("맞는 것은 보고하지 않는다")
    class Matching {

        @Test
        @DisplayName("금액이 같고 정산 완료면 불일치가 없다")
        void settledAndEqualIsClean() {
            assertThat(run(List.of(in(PI, 1_000_000L)),
                           List.of(ex(PI, 1_000_000L, "SETTLED"))))
                    .isEmpty();
        }

        @Test
        @DisplayName("정산됐는데 청산대기가 남아 있어도 불일치가 아니다 — unwind 는 회계계 몫")
        void settledWithRemainingPendingIsNotABreak() {
            // KFTC 정산은 청산대기를 되돌리는 분개를 남기지 않는다. "청산계정이 0 이
            // 되어야 한다" 로 짰다면 여기 있는 정상 거래가 전부 불일치로 잡힌다.
            assertThat(run(List.of(in(PI, 1_000_000L)),
                           List.of(ex(PI, 1_000_000L, "SETTLED"))))
                    .isEmpty();
        }

        @Test
        @DisplayName("양쪽 다 취소·거절이면 앞뒤가 맞는다")
        void reversedAndRejectedIsClean() {
            assertThat(run(List.of(in(PI, 0L)), List.of(ex(PI, 1_000_000L, "REJECTED"))))
                    .isEmpty();
        }

        @Test
        @DisplayName("거절된 건이 내부에 없는 것은 정상이다 — 남을 이유가 없다")
        void rejectedWithNoInternalIsClean() {
            assertThat(run(List.of(), List.of(ex(PI, 1_000_000L, "REJECTED")))).isEmpty();
        }

        @Test
        @DisplayName("취소된 건이 외부에 없는 것은 정상이다 — 망에 안 갔을 수 있다")
        void reversedWithNoExternalIsClean() {
            assertThat(run(List.of(in(PI, 0L)), List.of())).isEmpty();
        }
    }

    // ── 불일치 ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("어긋난 것은 잡는다")
    class Breaks {

        @Test
        @DisplayName("내부에만 있으면 MISSING_EXTERNAL — 출금됐는데 망에 안 갔다")
        void internalOnly() {
            List<ReconciliationBreak> breaks = run(List.of(in(PI, 1_000_000L)), List.of());

            assertThat(breaks).singleElement().satisfies(b -> {
                assertThat(b.type()).isEqualTo(ReconciliationBreakType.MISSING_EXTERNAL);
                assertThat(b.internalAmount()).isEqualTo(1_000_000L);
                assertThat(b.externalAmount()).isZero();
            });
        }

        @Test
        @DisplayName("외부에만 있으면 MISSING_INTERNAL — 망은 처리했는데 장부에 없다")
        void externalOnly() {
            List<ReconciliationBreak> breaks = run(List.of(), List.of(ex(PI, 1_000_000L, "SETTLED")));

            assertThat(breaks).singleElement().satisfies(b -> {
                assertThat(b.type()).isEqualTo(ReconciliationBreakType.MISSING_INTERNAL);
                assertThat(b.externalAmount()).isEqualTo(1_000_000L);
            });
        }

        @Test
        @DisplayName("금액이 다르면 AMOUNT_MISMATCH — 차액이 메시지에 남는다")
        void amountDiffers() {
            List<ReconciliationBreak> breaks =
                    run(List.of(in(PI, 1_000_000L)), List.of(ex(PI, 999_000L, "SETTLED")));

            assertThat(breaks).singleElement().satisfies(b -> {
                assertThat(b.type()).isEqualTo(ReconciliationBreakType.AMOUNT_MISMATCH);
                assertThat(b.difference()).isEqualTo(1_000L);
                assertThat(b.detail()).contains("1000000").contains("999000");
            });
        }

        @Test
        @DisplayName("내부는 취소했는데 외부는 정산되면 REVERSED_BUT_SETTLED — 가장 위험하다")
        void reversedButSettled() {
            List<ReconciliationBreak> breaks =
                    run(List.of(in(PI, 0L)), List.of(ex(PI, 1_000_000L, "SETTLED")));

            assertThat(breaks).singleElement().satisfies(b -> {
                assertThat(b.type()).isEqualTo(ReconciliationBreakType.REVERSED_BUT_SETTLED);
                assertThat(b.externalAmount()).isEqualTo(1_000_000L);
            });
        }
    }

    // ── 미결 ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("끝나지 않은 건")
    class StalePending {

        @Test
        @DisplayName("영업일이 끝났는데 미확정이면 STALE_PENDING — 미결제가 조용히 늙는 것을 막는다")
        void unfinalizedAfterDayClose() {
            List<ReconciliationBreak> breaks =
                    run(List.of(in(PI, 1_000_000L)), List.of(ex(PI, 1_000_000L, "REQUESTED")));

            assertThat(breaks).singleElement()
                    .satisfies(b -> assertThat(b.type())
                            .isEqualTo(ReconciliationBreakType.STALE_PENDING));
        }

        @Test
        @DisplayName("영업일이 진행 중이면 미확정은 정상이다 — 켜면 진행 중 거래가 전부 잡힌다")
        void unfinalizedDuringOpenDayIsClean() {
            assertThat(ReconciliationEngine.reconcile(
                    List.of(in(PI, 1_000_000L)),
                    List.of(ex(PI, 1_000_000L, "REQUESTED")),
                    false))
                    .isEmpty();
        }

        @Test
        @DisplayName("TIMEOUT 은 확정으로 본다 — 이미 판정이 난 것을 두 번 보고하지 않는다")
        void timeoutIsFinal() {
            assertThat(run(List.of(in(PI, 1_000_000L)),
                           List.of(ex(PI, 1_000_000L, "TIMEOUT"))))
                    .isEmpty();
        }
    }

    // ── 우선순위 ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("무엇부터 볼지가 정해져 있다")
    class Ordering {

        @Test
        @DisplayName("심각한 유형이 먼저 온다 — 수백 건이 나와도 위에서부터 보면 된다")
        void severeTypesComeFirst() {
            List<ReconciliationBreak> breaks = run(
                    List.of(in("PI-A", 1_000L), in("PI-C", 0L), in("PI-D", 5_000L)),
                    List.of(ex("PI-A", 900L, "SETTLED"),      // AMOUNT_MISMATCH
                            ex("PI-B", 2_000L, "SETTLED"),    // MISSING_INTERNAL
                            ex("PI-C", 3_000L, "SETTLED")));  // REVERSED_BUT_SETTLED
                                                              // PI-D → MISSING_EXTERNAL

            assertThat(breaks).extracting(ReconciliationBreak::type)
                    .containsExactly(
                            ReconciliationBreakType.REVERSED_BUT_SETTLED,
                            ReconciliationBreakType.MISSING_EXTERNAL,
                            ReconciliationBreakType.MISSING_INTERNAL,
                            ReconciliationBreakType.AMOUNT_MISMATCH);
        }

        @Test
        @DisplayName("같은 유형이면 금액 큰 것이 먼저 온다")
        void largerAmountsFirstWithinType() {
            List<ReconciliationBreak> breaks = run(
                    List.of(in("PI-SMALL", 1_000L), in("PI-BIG", 9_000_000L)), List.of());

            assertThat(breaks).extracting(ReconciliationBreak::paymentInstructionId)
                    .containsExactly("PI-BIG", "PI-SMALL");
        }
    }

    // ── 경계 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("양쪽 다 비어 있으면 불일치가 없다")
    void bothEmpty() {
        assertThat(run(List.of(), List.of())).isEmpty();
        assertThat(ReconciliationEngine.reconcile(null, null, true)).isEmpty();
    }

    @Test
    @DisplayName("여러 건이 섞여 있어도 맞는 것은 보고하지 않는다")
    void cleanEntriesAreNotReported() {
        List<ReconciliationBreak> breaks = run(
                List.of(in("PI-OK1", 1_000L), in("PI-OK2", 2_000L), in("PI-BAD", 3_000L)),
                List.of(ex("PI-OK1", 1_000L, "SETTLED"), ex("PI-OK2", 2_000L, "SETTLED")));

        assertThat(breaks).extracting(ReconciliationBreak::paymentInstructionId)
                .containsExactly("PI-BAD");
    }
}
