package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.PaymentSchedule;
import com.bank.deposit.domain.enums.PaymentStatus;
import com.bank.deposit.service.PaymentScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/payment-schedules")
@RequiredArgsConstructor
public class PaymentScheduleController {

    private final PaymentScheduleService paymentScheduleService;

    /** 계약별 납입 스케줄 전체 조회 */
    @GetMapping("/contracts/{contractId}")
    public ResponseEntity<List<PaymentSchedule>> getByContract(@PathVariable Long contractId) {
        return ResponseEntity.ok(paymentScheduleService.findByContract(contractId));
    }

    /** 계약별 납입 스케줄 상태별 조회 */
    @GetMapping("/contracts/{contractId}/status/{status}")
    public ResponseEntity<List<PaymentSchedule>> getByContractAndStatus(
            @PathVariable Long contractId,
            @PathVariable PaymentStatus status) {
        return ResponseEntity.ok(paymentScheduleService.findByContractAndStatus(contractId, status));
    }

    /**
     * 정기적금 납입 스케줄 생성.
     * 계약 생성 후 호출하여 전체 납입 회차를 등록한다.
     */
    @PostMapping("/contracts/{contractId}/generate")
    public ResponseEntity<List<PaymentSchedule>> generate(
            @PathVariable Long contractId,
            @RequestParam Long accountId,
            @RequestParam Integer contractPeriodMonth,
            @RequestParam BigDecimal monthlyAmount,
            @RequestParam(defaultValue = "false") boolean isAutoTransfer,
            @RequestParam(required = false) Long sourceAccountId,
            @RequestParam(required = false) Integer autoTransferDay,
            @RequestParam String startedAt) {
        List<PaymentSchedule> schedules = paymentScheduleService.createSchedules(
                contractId, accountId, contractPeriodMonth, monthlyAmount,
                isAutoTransfer, sourceAccountId, autoTransferDay,
                LocalDate.parse(startedAt));
        return ResponseEntity.ok(schedules);
    }

    /**
     * 수동 납입 처리.
     * PENDING 또는 OVERDUE 상태 스케줄에 대해 고객이 직접 납입할 때 호출한다.
     */
    @PostMapping("/{scheduleId}/pay")
    public ResponseEntity<PaymentSchedule> pay(
            @PathVariable Long scheduleId,
            @RequestParam Long sourceAccountId) {
        return ResponseEntity.ok(paymentScheduleService.pay(scheduleId, sourceAccountId));
    }
}
