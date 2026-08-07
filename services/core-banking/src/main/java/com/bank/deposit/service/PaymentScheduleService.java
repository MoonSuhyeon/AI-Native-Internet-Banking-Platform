package com.bank.deposit.service;

import com.bank.deposit.domain.entity.Contract;
import com.bank.deposit.domain.entity.PaymentSchedule;
import com.bank.deposit.domain.enums.PaymentStatus;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.repository.AccountRepository;
import com.bank.deposit.repository.ContractRepository;
import com.bank.deposit.repository.PaymentScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentScheduleService {

    private final PaymentScheduleRepository scheduleRepository;
    private final ContractRepository contractRepository;
    private final AccountRepository accountRepository;
    private final AutoTransferService autoTransferService;

    public List<PaymentSchedule> findByContract(Long contractId) {
        return scheduleRepository.findByContractIdOrderByPaymentRound(contractId);
    }

    public List<PaymentSchedule> findByContractAndStatus(Long contractId, PaymentStatus status) {
        return scheduleRepository.findByContractIdAndStatus(contractId, status);
    }

    /**
     * 정기적금 계약 생성 시 납입 스케줄 일괄 생성.
     *
     * @param contractId      계약 ID
     * @param accountId       납입 대상 적금 계좌 ID
     * @param contractPeriodMonth 계약 기간 (개월)
     * @param monthlyAmount   월 납입금액
     * @param isAutoTransfer  자동이체 여부
     * @param sourceAccountId 자동이체 출금 계좌 ID (isAutoTransfer=true일 때)
     * @param autoTransferDay 자동이체일 (1~31)
     * @param startedAt       계약 시작일
     */
    @Transactional
    public List<PaymentSchedule> createSchedules(Long contractId, Long accountId,
                                                  Integer contractPeriodMonth,
                                                  Long monthlyAmount,
                                                  boolean isAutoTransfer,
                                                  Long sourceAccountId,
                                                  Integer autoTransferDay,
                                                  LocalDate startedAt) {
        Contract contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONTRACT_NOT_FOUND));

        // 자동이체일 설정: autoTransferDay가 있으면 해당 일, 없으면 계약 시작일 기준
        int payDay = (autoTransferDay != null) ? autoTransferDay : startedAt.getDayOfMonth();

        List<PaymentSchedule> schedules = new ArrayList<>();
        for (int round = 1; round <= contractPeriodMonth; round++) {
            LocalDate base = startedAt.plusMonths(round);
            // 해당 월의 마지막 날을 넘지 않게 조정 (예: 31일인데 2월이면 28일)
            int adjustedDay = Math.min(payDay, base.lengthOfMonth());
            LocalDate scheduledDate = base.withDayOfMonth(adjustedDay);

            PaymentSchedule schedule = PaymentSchedule.builder()
                    .contractId(contractId)
                    .accountId(accountId)
                    .paymentRound(round)
                    .scheduledDate(scheduledDate)
                    .scheduledAmount(monthlyAmount)
                    .isAutoTransfer(isAutoTransfer)
                    .sourceAccountId(isAutoTransfer ? sourceAccountId : null)
                    .build();
            schedules.add(schedule);
        }

        // 계약에 sourceAccountId 저장
        if (isAutoTransfer && sourceAccountId != null) {
            contract.updateSourceAccount(sourceAccountId);
        }

        return scheduleRepository.saveAll(schedules);
    }

    /**
     * 수동 납입 처리.
     * PENDING 또는 OVERDUE 상태의 스케줄에 대해 고객이 직접 납입할 때 호출.
     */
    @Transactional
    public PaymentSchedule pay(Long scheduleId, Long sourceAccountId) {
        return autoTransferService.executeManualPayment(scheduleId, sourceAccountId);
    }
}
