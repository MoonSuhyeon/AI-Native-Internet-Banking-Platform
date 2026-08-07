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


/**
 * EOD 배치 일별 자동 실행 스케줄러.
 *
 * loan.batch.eod-cron (application.yml) 으로 실행 시각 제어.
 * 기본값: "0 0 1 * * *" — 매일 새벽 1시 KST.
 * zone 을 명시하지 않으면 cron 은 JVM 기본 타임존(컨테이너 = UTC)으로 해석돼
 * 실제로는 10시에 떴다. 하루를 마감하는 배치가 오전에 도는 셈이었다.
 *
 * 동일 baseDate 를 JobParameter 로 사용하므로 같은 날 이미 COMPLETED 된 잡은
 * Spring Batch 가 JobInstanceAlreadyCompleteException 으로 중복 실행을 막는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EodBatchScheduler {


    private final JobLauncher jobLauncher;
    @Qualifier("loanEodJob")
    private final Job loanEodJob;

    @Scheduled(cron = "${loan.batch.eod-cron}", zone = "Asia/Seoul")
    public void runEod() {
        String baseDate = BusinessDate.today();
        log.info("[EOD-Scheduler] baseDate={} 배치 시작", baseDate);
        try {
            JobParameters params = new JobParametersBuilder()
                    .addString("baseDate", baseDate)
                    .toJobParameters();
            var execution = jobLauncher.run(loanEodJob, params);
            log.info("[EOD-Scheduler] baseDate={} 완료: status={} id={}",
                    baseDate, execution.getStatus(), execution.getId());
        } catch (Exception e) {
            log.error("[EOD-Scheduler] baseDate={} 실패: {}", baseDate, e.getMessage(), e);
        }
    }
}
