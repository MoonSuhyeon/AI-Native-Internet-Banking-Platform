package com.bank.ai.drift;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PsiDriftJobSchedulerTest {

    private final JobLauncher jobLauncher = mock(JobLauncher.class);
    private final Job psiDriftJob         = mock(Job.class);
    private final DriftProperties props   = new DriftProperties(
        true, "v1", 0.10, 0.20, 0.05, 6, "0 0 2 * * MON", List.of("creditScore"));
    private final PsiDriftJobScheduler scheduler =
        new PsiDriftJobScheduler(jobLauncher, psiDriftJob, props);

    @Test
    void runWeekly_jobLauncher_호출되고_calcWeek파라미터가_이번주_월요일() throws Exception {
        scheduler.runWeekly();

        ArgumentCaptor<JobParameters> captor = ArgumentCaptor.forClass(JobParameters.class);
        verify(jobLauncher, times(1)).run(eq(psiDriftJob), captor.capture());

        String expected = LocalDate.now().with(DayOfWeek.MONDAY).toString();
        assertThat(captor.getValue().getString("calcWeek")).isEqualTo(expected);
    }

    @Test
    void runWeekly_jobLauncher_예외시_로그만_남기고_전파안함() throws Exception {
        doThrow(new RuntimeException("batch error")).when(jobLauncher).run(any(), any());

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(scheduler::runWeekly);
    }
}
