package com.bank.ai.drift;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@EnableBatchProcessing
@RequiredArgsConstructor
public class PsiDriftJobConfig {

    private final PsiDriftBatchJob psiDriftBatchJob;

    @Bean
    public Job psiDriftJob(JobRepository jobRepository, Step psiDriftStep) {
        return new JobBuilder("psiDriftJob", jobRepository)
            .start(psiDriftStep)
            .build();
    }

    @Bean
    public Step psiDriftStep(JobRepository jobRepository,
                              PlatformTransactionManager txManager) {
        return new StepBuilder("psiDriftStep", jobRepository)
            .tasklet(psiDriftBatchJob, txManager)
            .build();
    }
}
