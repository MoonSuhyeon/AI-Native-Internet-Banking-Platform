package com.bank.loan.batch.scheduler;

import com.bank.common.time.BusinessDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * EOM(월마감) 배치 자동 실행 스케줄러.
 *
 * loan.batch.eom-cron 으로 실행 시각 제어 — 기본 매월 1일 03:00 KST.
 * 어제 (= 전월 말일) 가 속한 달을 baseMonth 로 사용.
 *
 * 동일 baseMonth 가 이미 COMPLETED 된 경우 Spring Batch 가 자동으로 중복 실행 차단.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EomBatchScheduler {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final JobLauncher jobLauncher;
    @Qualifier("loanEomJob")
    private final Job loanEomJob;

    @Scheduled(cron = "${loan.batch.eom-cron}", zone = "Asia/Seoul")
    public void runEom() {
        // 매월 1일 03:00 KST 실행 가정 — 어제 = 전월 말일 → 그 달이 baseMonth
        String baseMonth = BusinessDate.todayDate().minusDays(1).format(MONTH_FMT);
        log.info("[EOM-Scheduler] baseMonth={} 배치 시작", baseMonth);
        try {
            JobParameters params = new JobParametersBuilder()
                    .addString("baseMonth", baseMonth)
                    .toJobParameters();
            var execution = jobLauncher.run(loanEomJob, params);
            log.info("[EOM-Scheduler] baseMonth={} 완료: status={} id={}",
                    baseMonth, execution.getStatus(), execution.getId());
        } catch (Exception e) {
            log.error("[EOM-Scheduler] baseMonth={} 실패: {}", baseMonth, e.getMessage(), e);
        }
    }
}
