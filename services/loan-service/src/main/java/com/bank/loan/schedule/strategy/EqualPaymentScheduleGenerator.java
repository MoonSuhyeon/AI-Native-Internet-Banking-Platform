package com.bank.loan.schedule.strategy;

import com.bank.loan.calendar.service.BusinessDayService;
import com.bank.loan.contract.domain.LoanContract;
import com.bank.loan.schedule.domain.RepaymentSchedule;
import com.bank.loan.schedule.service.EqualPaymentCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 원리금균등(EQUAL) 회차 생성기.
 *
 * 매 회차 납입액(원금+이자) 이 일정하고, 회차가 갈수록 원금 비중이 증가한다.
 * 회차당 금액 계산은 {@link EqualPaymentCalculator} 공식 유틸에 위임 — 라운딩 정책 동일.
 *
 * dueDate 산정 후 비영업일이면 {@link BusinessDayService#nextBusinessDay} 로 이동 (한국 여신 관행 — following).
 * 이동된 회차는 holiday_adjusted_yn = 'Y'.
 */
@Component
@RequiredArgsConstructor
public class EqualPaymentScheduleGenerator implements RepaymentScheduleGenerator {

    public static final String METHOD = "EQUAL";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final BusinessDayService businessDayService;

    @Override
    public String supportedMethod() {
        return METHOD;
    }

    @Override
    public List<RepaymentSchedule> generate(LoanContract contract) {
        List<EqualPaymentCalculator.Installment> installments = EqualPaymentCalculator.calculate(
                contract.getContractedAmount(),
                contract.getTotalRateBps(),
                contract.getContractedPeriodMo()
        );

        LocalDate startDate = LocalDate.parse(contract.getCntrStartDate(), DATE);
        List<RepaymentSchedule> rows = new ArrayList<>(installments.size());

        for (int i = 0; i < installments.size(); i++) {
            EqualPaymentCalculator.Installment inst = installments.get(i);
            int installmentNo = i + 1;
            String rawDueDate = startDate.plusMonths(installmentNo).format(DATE);
            String adjustedDueDate = businessDayService.nextBusinessDay(rawDueDate);
            Boolean adjustedYn = adjustedDueDate.equals(rawDueDate)
                    ? false
                    : true;

            rows.add(RepaymentSchedule.builder()
                    .cntrId(contract.getCntrId())
                    .installmentNo(installmentNo)
                    .dueDate(adjustedDueDate)
                    .scheduledPrincipal(inst.scheduledPrincipal())
                    .scheduledInterest(inst.scheduledInterest())
                    .scheduledTotal(inst.scheduledTotal())
                    .remainingBalance(inst.remainingBalance())
                    .appliedRateBps(contract.getTotalRateBps())
                    .rschStatusCd(RepaymentSchedule.STATUS_DUE)
                    .rschVersionCd(RepaymentSchedule.VERSION_INITIAL)
                    .holidayAdjustedYn(adjustedYn)
                    .build());
        }

        return rows;
    }
}
