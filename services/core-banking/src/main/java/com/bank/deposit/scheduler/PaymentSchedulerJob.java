package com.bank.deposit.scheduler;

import com.bank.deposit.domain.entity.PaymentSchedule;
import com.bank.deposit.domain.enums.PaymentStatus;
import com.bank.deposit.repository.PaymentScheduleRepository;
import com.bank.deposit.service.AutoTransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentSchedulerJob {

    private final PaymentScheduleRepository scheduleRepository;
    private final AutoTransferService autoTransferService;
    private final Clock clock;

    /**
     * 매일 오전 9시 실행.
     *
     * 1) 자동이체 대상 스케줄: 당일 포함 예정일 경과, 아직 PENDING → 자동이체 실행
     * 2) 수동 납입 지연 스케줄: 예정일 지남, 아직 PENDING → OVERDUE 처리
     */
    @Scheduled(cron = "0 0 9 * * *")
    public void run() {
        LocalDate today = LocalDate.now(clock);
        log.info("[PaymentScheduler] 실행 시작 — {}", today);

        processAutoTransfers(today);
        processManualOverdue(today);

        log.info("[PaymentScheduler] 실행 완료 — {}", today);
    }

    private void processAutoTransfers(LocalDate today) {
        List<PaymentSchedule> due = scheduleRepository.findAutoTransferDue(today, PaymentStatus.PENDING);
        log.info("[PaymentScheduler] 자동이체 대상: {}건", due.size());
        for (PaymentSchedule schedule : due) {
            try {
                autoTransferService.executeAutoTransfer(schedule);
            } catch (Exception e) {
                log.error("[PaymentScheduler] 자동이체 처리 중 예외 scheduleId={}: {}",
                        schedule.getScheduleId(), e.getMessage(), e);
            }
        }
    }

    private void processManualOverdue(LocalDate today) {
        List<PaymentSchedule> overdue = scheduleRepository.findManualOverdue(today, PaymentStatus.PENDING);
        log.info("[PaymentScheduler] 수동 납입 지연: {}건", overdue.size());
        for (PaymentSchedule schedule : overdue) {
            try {
                autoTransferService.markManualOverdue(schedule);
            } catch (Exception e) {
                log.error("[PaymentScheduler] 납입 지연 처리 중 예외 scheduleId={}: {}",
                        schedule.getScheduleId(), e.getMessage(), e);
            }
        }
    }
}
