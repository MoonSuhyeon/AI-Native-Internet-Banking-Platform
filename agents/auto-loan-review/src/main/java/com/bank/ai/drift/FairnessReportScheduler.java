package com.bank.ai.drift;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.drift", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FairnessReportScheduler {

    private final FairnessReportService fairnessReportService;

    @Scheduled(cron = "${ai.drift.fairness-cron:0 0 3 1 * *}")
    public void runMonthly() {
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        try {
            var reports = fairnessReportService.generateMonthlyReport(lastMonth);
            log.info("[Fairness Scheduler] 리포트 생성 완료 month={} count={}", lastMonth, reports.size());
        } catch (Exception e) {
            log.error("[Fairness Scheduler] 리포트 생성 실패 month={}", lastMonth, e);
        }
    }
}
