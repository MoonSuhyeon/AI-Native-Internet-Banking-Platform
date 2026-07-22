package com.bank.ai.drift;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FairnessReportSchedulerTest {

    private final FairnessReportService fairnessReportService = mock(FairnessReportService.class);
    private final FairnessReportScheduler scheduler = new FairnessReportScheduler(fairnessReportService);

    @Test
    void runMonthly_generateMonthlyReport_호출되고_전월_YearMonth_전달() {
        when(fairnessReportService.generateMonthlyReport(any())).thenReturn(List.of());

        scheduler.runMonthly();

        YearMonth expected = YearMonth.now().minusMonths(1);
        verify(fairnessReportService, times(1)).generateMonthlyReport(expected);
    }

    @Test
    void runMonthly_예외시_로그만_남기고_전파안함() {
        when(fairnessReportService.generateMonthlyReport(any()))
            .thenThrow(new RuntimeException("db error"));

        assertDoesNotThrow(scheduler::runMonthly);
    }
}
