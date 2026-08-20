package com.bank.deposit.domain;

import com.bank.deposit.domain.entity.EarlyTerminationRate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 중도해지 예상 명세.
 *
 * <p><b>무엇을 지키는가.</b> 고객에게 보여 주는 숫자가 근거 있는 값이도록 한다.
 * 중도해지는 되돌릴 수 없으므로, 여기서 나온 숫자를 보고 누른 결정이 나중에 다른
 * 금액으로 확정되면 고객은 은행이 속였다고 읽는다.
 */
class EarlyTerminationEstimatorTest {

    private static final BigDecimal TAX = new BigDecimal("15.40");
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate MATURITY = LocalDate.of(2027, 1, 1);

    private static EarlyTerminationRate band(String from, String to, String mult, String min) {
        return EarlyTerminationRate.builder()
                .elapsedRatioFrom(new BigDecimal(from))
                .elapsedRatioTo(new BigDecimal(to))
                .rateMultiplier(new BigDecimal(mult))
                .minRate(new BigDecimal(min))
                .description(from + "~" + to)
                .build();
    }

    private static final List<EarlyTerminationRate> POLICY = List.of(
            band("0.000", "0.250", "0.100", "0.10"),
            band("0.250", "0.500", "0.200", "0.15"),
            band("0.500", "0.750", "0.400", "0.20"),
            band("0.750", "1.000", "0.600", "0.25"));

    @Nested
    @DisplayName("적용 이율")
    class RateSelection {

        @Test
        @DisplayName("경과가 짧을수록 이율이 낮다")
        void earlierMeansLower() {
            BigDecimal early = EarlyTerminationEstimator.applyRate(
                    new BigDecimal("3.00"), POLICY.get(0));
            BigDecimal late = EarlyTerminationEstimator.applyRate(
                    new BigDecimal("3.00"), POLICY.get(3));

            assertThat(early).isLessThan(late);
        }

        @Test
        @DisplayName("최저보장이율 아래로는 내려가지 않는다")
        void minRateFloor() {
            // 약정이율 0.30% × 0.6 = 0.18% 지만 이 구간의 최저는 0.25% 다.
            assertThat(EarlyTerminationEstimator.applyRate(
                    new BigDecimal("0.30"), POLICY.get(3)))
                    .isEqualByComparingTo("0.25");
        }

        @Test
        @DisplayName("구간 경계는 아래쪽 구간에 속한다")
        void boundaryBelongsToLowerBand() {
            // 0.250 이 두 구간 모두에 걸리면 어떤 이율이 나올지 순서에 달리게 된다.
            assertThat(POLICY.get(0).covers(new BigDecimal("0.250"))).isFalse();
            assertThat(POLICY.get(1).covers(new BigDecimal("0.250"))).isTrue();
        }

        @Test
        @DisplayName("구간이 없으면 임의로 메우지 않고 실패한다")
        void missingBandFailsLoudly() {
            // 정책 표에 구멍이 있는데 조용히 메우면 근거 없는 이율을 보여 주고도
            // 아무도 모른다.
            assertThatThrownBy(() -> EarlyTerminationEstimator.selectRate(
                    List.of(band("0.000", "0.250", "0.100", "0.10")), new BigDecimal("0.600")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("이율 구간이 없다");
        }
    }

    @Nested
    @DisplayName("이자 계산")
    class Interest {

        @Test
        @DisplayName("중도해지 이자는 만기 이자보다 적다")
        void lessThanMaturity() {
            var e = EarlyTerminationEstimator.estimate(
                    List.of(new EarlyTerminationEstimator.Deposit(10_000_000L, START)),
                    new BigDecimal("3.00"), TAX, START, MATURITY,
                    LocalDate.of(2026, 4, 1), POLICY);

            assertThat(e.earlyTermination()).isTrue();
            assertThat(e.interestBeforeTax()).isLessThan(e.interestIfHeldToMaturity());
        }

        @Test
        @DisplayName("적금은 회차별 예치일수로 따로 계산한다")
        void perDepositForSavings() {
            // 전체 납입액에 전체 기간을 곱하면 나중에 낸 돈까지 처음부터 예치된
            // 것으로 쳐서 이자가 부풀려진다.
            LocalDate on = LocalDate.of(2026, 7, 1);
            var perRound = EarlyTerminationEstimator.estimate(List.of(
                    new EarlyTerminationEstimator.Deposit(1_000_000L, START),
                    new EarlyTerminationEstimator.Deposit(1_000_000L, LocalDate.of(2026, 6, 1))
            ), new BigDecimal("3.00"), TAX, START, MATURITY, on, POLICY);

            var lumpFromStart = EarlyTerminationEstimator.estimate(
                    List.of(new EarlyTerminationEstimator.Deposit(2_000_000L, START)),
                    new BigDecimal("3.00"), TAX, START, MATURITY, on, POLICY);

            assertThat(perRound.interestBeforeTax())
                    .isLessThan(lumpFromStart.interestBeforeTax());
        }

        @Test
        @DisplayName("해지일 이후에 낸 회차는 이자를 깎지 않는다")
        void futureDepositAddsNothing() {
            LocalDate on = LocalDate.of(2026, 4, 1);
            var withFuture = EarlyTerminationEstimator.estimate(List.of(
                    new EarlyTerminationEstimator.Deposit(1_000_000L, START),
                    new EarlyTerminationEstimator.Deposit(1_000_000L, LocalDate.of(2026, 12, 1))
            ), new BigDecimal("3.00"), TAX, START, MATURITY, on, POLICY);

            // 음수 일수를 그대로 곱하면 이자가 깎여 실수령액이 원금보다 작아진다.
            assertThat(withFuture.interestBeforeTax()).isPositive();
            assertThat(withFuture.netAmount()).isGreaterThanOrEqualTo(withFuture.principal());
        }

        @Test
        @DisplayName("세금은 이자에서만 뗀다")
        void taxOnInterestOnly() {
            var e = EarlyTerminationEstimator.estimate(
                    List.of(new EarlyTerminationEstimator.Deposit(10_000_000L, START)),
                    new BigDecimal("3.00"), TAX, START, MATURITY,
                    LocalDate.of(2026, 7, 1), POLICY);

            // 원금에까지 세금이 붙으면 실수령액이 원금 아래로 떨어진다.
            assertThat(e.netAmount()).isEqualTo(e.principal() + e.interestBeforeTax() - e.taxAmount());
            assertThat(e.taxAmount()).isLessThan(e.interestBeforeTax());
        }

        @Test
        @DisplayName("만기에 도달하면 중도해지가 아니다")
        void atMaturityIsNotEarly() {
            var e = EarlyTerminationEstimator.estimate(
                    List.of(new EarlyTerminationEstimator.Deposit(10_000_000L, START)),
                    new BigDecimal("3.00"), TAX, START, MATURITY, MATURITY, POLICY);

            assertThat(e.earlyTermination()).isFalse();
            assertThat(e.appliedRate()).isEqualByComparingTo("3.00");
        }

        @Test
        @DisplayName("만기가 없는 계약은 약정이율을 그대로 쓴다")
        void noMaturityUsesContractRate() {
            // 자유적금처럼 만기가 정해지지 않은 계약은 경과 비율을 정의할 수 없다.
            var e = EarlyTerminationEstimator.estimate(
                    List.of(new EarlyTerminationEstimator.Deposit(1_000_000L, START)),
                    new BigDecimal("2.00"), TAX, START, null,
                    LocalDate.of(2026, 7, 1), POLICY);

            assertThat(e.earlyTermination()).isFalse();
            assertThat(e.appliedRate()).isEqualByComparingTo("2.00");
        }
    }
}
