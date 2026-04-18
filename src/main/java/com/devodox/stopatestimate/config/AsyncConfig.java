package com.devodox.stopatestimate.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Async wiring dedicated to install-time reconcile retries. Kept separate from the scheduler
 * thread pool so a slow retry (up to ~17s of sleeps) cannot block the cutoff tick.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("lifecycleReconcileExecutor")
    public TaskExecutor lifecycleReconcileExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("lifecycle-reconcile-");
        executor.initialize();
        return executor;
    }
}
