package com.bank.deposit.domain;

import com.bank.deposit.domain.entity.EarlyTerminationRate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 중도해지 예상 명세 계산.
 *
 * <p><b>왜 필요한가.</b> 해지 화면의 "해지상세조회" 버튼이 아무 데도 연결돼 있지
 * 않아, 고객은 얼마를 손해 보고 해지하는지 모른 채 "해지" 를 눌러야 했다. 중도해지는
 * 되돌릴 수 없는데 결과를 미리 볼 수 없었다.
 *
 * <p><b>예상이라고 부르는 이유.</b> 실제 지급액은 해지 당일의 잔액과 세율로 확정된다.
 * 여기서 내는 값은 오늘 해지한다면 얼마인지이고, 하루만 지나도 달라진다. 화면이
 * 이것을 확정 금액처럼 보여 주면 1원이라도 어긋났을 때 고객은 은행이 속였다고 읽는다.
 *
 * <p>이자는 실제 일수로 계산한다(연 365일 기준). 적금은 회차마다 예치 기간이 다르므로
 * 회차별로 따로 계산해 더한다 — 전체 납입액에 전체 기간을 곱하면 나중에 낸 돈까지
 * 처음부터 예치된 것으로 쳐서 이자가 부풀려진다.
 */
public final class EarlyTerminationEstimator {

    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int RATE_SCALE = 4;

    private EarlyTerminationEstimator() {
    }

    /** 예치 원금 한 건 — 예금은 한 건, 적금은 납입 회차마다 한 건. */
    public record Deposit(long amount, LocalDate depositedAt) {
    }

    public record Estimate(
            BigDecimal appliedRate,
            String rateDescription,
            long principal,
            long interestBeforeTax,
            long taxAmount,
            long netAmount,
            /** 만기까지 갔다면 받았을 이자. 얼마를 포기하는지 보여 준다. */
            long interestIfHeldToMaturity,
            boolean earlyTermination
    ) {
    }

    /**
     * 경과 비율 = 실제 보유일 / 계약 전체일.
     *
     * <p>1.0 이상이면 만기에 도달한 것이라 중도해지가 아니다.
     */
    public static BigDecimal elapsedRatio(LocalDate startedAt, LocalDate maturityAt, LocalDate on) {
        if (maturityAt == null || !maturityAt.isAfter(startedAt)) {
            // 만기가 없는 계약(자유적금 등)은 경과 비율을 정의할 수 없다.
            return BigDecimal.ONE;
        }
        long total = ChronoUnit.DAYS.between(startedAt, maturityAt);
        long elapsed = Math.max(0, ChronoUnit.DAYS.between(startedAt, on));
        return BigDecimal.valueOf(elapsed)
                .divide(BigDecimal.valueOf(total), RATE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 적용 이율을 고른다.
     *
     * <p>구간을 못 찾으면 <b>가장 불리한 구간</b>이 아니라 예외를 낸다. 정책 표에
     * 구멍이 있는데 임의로 메우면, 고객에게 근거 없는 이율을 보여 주고도 아무도
     * 모른다.
     */
    public static EarlyTerminationRate selectRate(
            List<EarlyTerminationRate> policy, BigDecimal elapsedRatio) {

        return policy.stream()
                .filter(r -> r.covers(elapsedRatio))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "중도해지 이율 구간이 없다 — 경과비율 " + elapsedRatio
                                + ". 정책 표(early_termination_rate)를 확인해야 한다."));
    }

    /** 약정이율 × 배수, 단 최저보장이율보다 낮아지지 않는다. */
    public static BigDecimal applyRate(BigDecimal contractRate, EarlyTerminationRate band) {
        BigDecimal reduced = contractRate.multiply(band.getRateMultiplier())
                .setScale(2, RoundingMode.HALF_UP);
        return reduced.max(band.getMinRate());
    }

    /**
     * 예상 명세.
     *
     * @param deposits    예치 건들. 적금은 납입 회차마다 하나.
     * @param contractRate 약정이율(연 %)
     * @param taxRate     적용 세율(%)
     * @param on          해지 예정일
     */
    public static Estimate estimate(
            List<Deposit> deposits,
            BigDecimal contractRate,
            BigDecimal taxRate,
            LocalDate startedAt,
            LocalDate maturityAt,
            LocalDate on,
            List<EarlyTerminationRate> policy) {

        BigDecimal ratio = elapsedRatio(startedAt, maturityAt, on);
        boolean early = ratio.compareTo(BigDecimal.ONE) < 0;

        BigDecimal appliedRate = contractRate;
        String description = "만기 도달 — 약정이율 전액 적용";
        if (early) {
            EarlyTerminationRate band = selectRate(policy, ratio);
            appliedRate = applyRate(contractRate, band);
            description = band.getDescription();
        }

        long principal = deposits.stream().mapToLong(Deposit::amount).sum();
        long interest = interestOf(deposits, appliedRate, on);
        long atMaturity = maturityAt == null
                ? interest
                : interestOf(deposits, contractRate, maturityAt);

        long tax = BigDecimal.valueOf(interest)
                .multiply(taxRate)
                .divide(HUNDRED, 0, RoundingMode.DOWN)
                .longValue();

        return new Estimate(
                appliedRate, description, principal, interest, tax,
                principal + interest - tax, atMaturity, early);
    }

    /** 예치 건별로 실제 예치일수만큼 계산해 더한다. 원 단위 미만은 버린다. */
    private static long interestOf(List<Deposit> deposits, BigDecimal rate, LocalDate on) {
        long total = 0;
        for (Deposit d : deposits) {
            long days = ChronoUnit.DAYS.between(d.depositedAt(), on);
            if (days <= 0) {
                // 해지일 이후에 낸 회차는 이자가 붙지 않는다. 음수 일수를 그대로
                // 곱하면 이자가 깎여 총액이 원금보다 작아진다.
                continue;
            }
            total += BigDecimal.valueOf(d.amount())
                    .multiply(rate).divide(HUNDRED, RATE_SCALE, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(days))
                    .divide(DAYS_PER_YEAR, 0, RoundingMode.DOWN)
                    .longValue();
        }
        return total;
    }
}
