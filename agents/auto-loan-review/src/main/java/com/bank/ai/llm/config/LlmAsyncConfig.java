package com.bank.ai.llm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * LLM 비동기 파이프라인 스레드풀 설정 — plan/llm-pipeline.md §7.1.
 *
 * <p>{@link com.bank.ai.review.listener.AutoReviewEventListener} 의 {@code @Async("llmExecutor")}
 * 가 본 빈을 참조한다. 슬롯 크기 기준:
 * <ul>
 *   <li>core=4: 피크 시간대 동시 심사 건수 고려</li>
 *   <li>max=8: 버스트 허용 (inference-server + LLM 각 1~2 s 내 완료 가정)</li>
 *   <li>queue=200: 큐 적체 허용 한도 (초과 시 CallerRunsPolicy)</li>
 * </ul>
 * 서비스 종료 시 진행 중인 LLM 파이프라인을 최대 30 초 대기 후 강제 종료.
 */
@Configuration
@EnableAsync
public class LlmAsyncConfig implements AsyncConfigurer {

    @Bean(name = "llmExecutor")
    public ThreadPoolTaskExecutor llmExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("llm-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Shadow Mode 전용 스레드풀 — {@link com.bank.ai.shadow.ShadowModeService} 참조.
     * prod llmExecutor 와 분리하여 shadow 부하가 prod 에 영향을 주지 않도록 함.
     */
    @Bean(name = "shadowExecutor")
    public ThreadPoolTaskExecutor shadowExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("shadow-");
        executor.setWaitForTasksToCompleteOnShutdown(false);  // shadow는 shutdown 시 즉시 포기
        executor.initialize();
        return executor;
    }

    /** {@code @Async} 에 executor 이름 지정 없이 쓸 경우의 기본 풀. */
    @Override
    public Executor getAsyncExecutor() {
        return llmExecutor();
    }
}
