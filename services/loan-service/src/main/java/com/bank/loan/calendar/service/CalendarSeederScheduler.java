package com.bank.loan.calendar.service;

import com.bank.common.time.BusinessDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


/**
 * 영업일 캘린더 자동 시드 스케줄러.
 *
 * loan.batch.calendar-seed-cron 으로 실행 시각 제어 — 기본 매년 12월 1일 04:00 KST.
 * 매번 (현재 연도 + 1) 의 1년치 캘린더를 시드한다 (UNIQUE 멱등).
 *
 * EOD/EOM 잡과 달리 Spring Batch 잡으로 포장하지 않는다 — 단일 작업, 1년에 1번 호출, 멱등.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CalendarSeederScheduler {


    private final CalendarSeederBatchService seederService;

    @Scheduled(cron = "${loan.batch.calendar-seed-cron}", zone = "Asia/Seoul")
    public void runYearlySeed() {
        int nextYear = BusinessDate.todayDate().getYear() + 1;
        log.info("[calendar-seeder-scheduler] year={} 시드 시작", nextYear);
        try {
            var result = seederService.run(nextYear);
            log.info("[calendar-seeder-scheduler] year={} 완료 inserted={} skipped={}",
                    result.year(), result.inserted(), result.skipped());
        } catch (Exception e) {
            log.error("[calendar-seeder-scheduler] year={} 실패: {}", nextYear, e.getMessage(), e);
        }
    }
}
