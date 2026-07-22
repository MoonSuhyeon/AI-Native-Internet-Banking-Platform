package com.bank.ai.drift;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.drift", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PsiDriftJobScheduler {

    private final JobLauncher jobLauncher;
    private final Job psiDriftJob;
    private final DriftProperties props;

    @Scheduled(cron = "${ai.drift.psi-cron:0 0 2 * * MON}")
    public void runWeekly() {
        String calcWeek = LocalDate.now().with(DayOfWeek.MONDAY).toString();
        JobParameters params = new JobParametersBuilder()
            .addString("calcWeek", calcWeek)
            .toJobParameters();
        try {
            jobLauncher.run(psiDriftJob, params);
            log.info("[PSI Scheduler] 잡 기동 완료 calcWeek={}", calcWeek);
        } catch (Exception e) {
            log.error("[PSI Scheduler] 잡 기동 실패 calcWeek={}", calcWeek, e);
        }
    }
}
