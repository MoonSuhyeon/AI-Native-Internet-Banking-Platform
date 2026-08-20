package com.bank.deposit.dto.response;

import com.bank.deposit.domain.EarlyTerminationEstimator;
import com.bank.deposit.domain.entity.Contract;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 해지 예상 명세.
 *
 * <p><b>예상이다.</b> 실제 지급액은 해지 당일의 잔액과 세율로 확정되고, 하루만 지나도
 * 달라진다. {@code estimatedOn} 을 함께 주는 이유가 그것이다 — 화면이 어느 날짜
 * 기준인지 밝히지 않으면 고객은 이 숫자를 확정 금액으로 읽는다.
 *
 * @param forgoneInterest 만기까지 갔다면 더 받았을 이자. 얼마를 포기하는지 보여 준다.
 */
public record EarlyTerminationEstimateResponse(
        Long accountId,
        String accountNumber,
        LocalDate estimatedOn,
        boolean earlyTermination,
        boolean terminable,
        BigDecimal contractRate,
        BigDecimal appliedRate,
        String rateDescription,
        LocalDate startedAt,
        LocalDate maturityAt,
        long principal,
        long interestBeforeTax,
        BigDecimal taxRate,
        long taxAmount,
        long netAmount,
        long interestIfHeldToMaturity,
        long forgoneInterest
) {

    public static EarlyTerminationEstimateResponse of(
            Long accountId, String accountNumber, Contract contract,
            EarlyTerminationEstimator.Estimate e, LocalDate on, boolean terminable) {

        return new EarlyTerminationEstimateResponse(
                accountId,
                accountNumber,
                on,
                e.earlyTermination(),
                terminable,
                contract.getFinalInterestRate(),
                e.appliedRate(),
                e.rateDescription(),
                contract.getStartedAt(),
                contract.getMaturityAt(),
                e.principal(),
                e.interestBeforeTax(),
                contract.getAppliedTaxRate(),
                e.taxAmount(),
                e.netAmount(),
                e.interestIfHeldToMaturity(),
                Math.max(0, e.interestIfHeldToMaturity() - e.interestBeforeTax()));
    }
}
