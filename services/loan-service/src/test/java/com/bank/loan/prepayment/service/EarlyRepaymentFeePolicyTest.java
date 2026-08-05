package com.bank.loan.prepayment.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 중도상환 수수료 정책 단위 테스트.
 *
 * <p>산식: fee = amount × 150bps × remainingMonths / totalMonths / 10000
 */
class EarlyRepaymentFeePolicyTest {

    private static final long AMOUNT = 12_000_000L;

    @Test
    @DisplayName("잔여기간 100% — 원금의 150bps 전액")
    void 잔여기간_전체() {
        // 12,000,000 × 150 × 12 / 12 / 10000 = 180,000
        assertThat(EarlyRepaymentFeePolicy.calculate(AMOUNT, 12, 12)).isEqualTo(180_000L);
    }

    @Test
    @DisplayName("잔여기간 절반 — 비례 감소")
    void 잔여기간_절반() {
        assertThat(EarlyRepaymentFeePolicy.calculate(AMOUNT, 12, 6)).isEqualTo(90_000L);
    }

    @Test
    @DisplayName("잔여기간이 총기간을 넘으면 총기간으로 상한 — 만기 전액 기준을 넘지 않는다")
    void 잔여기간_상한() {
        // 계약 시작일이 미래면 호출부의 elapsedMonths 가 음수가 되어 잔여 > 총기간으로 넘어온다.
        long capped = EarlyRepaymentFeePolicy.calculate(AMOUNT, 12, 18);
        long full   = EarlyRepaymentFeePolicy.calculate(AMOUNT, 12, 12);

        assertThat(capped)
                .as("잔여 18개월이어도 12개월치를 넘지 않는다")
                .isEqualTo(full)
                .isEqualTo(180_000L);
    }

    @Test
    @DisplayName("만기 경과분은 수수료 없음 — 만기 후 상환은 '중도'가 아니다")
    void 잔여기간_0() {
        assertThat(EarlyRepaymentFeePolicy.calculate(AMOUNT, 12, 0)).isZero();
        assertThat(EarlyRepaymentFeePolicy.calculate(AMOUNT, 12, -3)).isZero();
    }

    @Test
    @DisplayName("금액·총기간이 유효하지 않으면 0")
    void 유효하지_않은_입력() {
        assertThat(EarlyRepaymentFeePolicy.calculate(0L, 12, 12)).isZero();
        assertThat(EarlyRepaymentFeePolicy.calculate(-1L, 12, 12)).isZero();
        assertThat(EarlyRepaymentFeePolicy.calculate(AMOUNT, 0, 12)).isZero();
    }

    @Test
    @DisplayName("반올림은 HALF_EVEN")
    void 반올림() {
        // 1,000,000 × 150 × 1 / 7 / 10000 = 2142.857... → 2143
        assertThat(EarlyRepaymentFeePolicy.calculate(1_000_000L, 7, 1)).isEqualTo(2_143L);
    }
}
