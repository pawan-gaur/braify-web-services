package com.braify.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configures the Spring {@code @Async} thread pool used by
 * {@link com.braify.feature.bulkemail.service.BulkEmailProcessor#processJobAsync}.
 *
 * <p>Each call to {@code processJobAsync} consumes one thread from this pool
 * for the lifetime of the job.  Inside the job, a separate per-job
 * {@link java.util.concurrent.ExecutorService} of size {@code CONCURRENCY}
 * fans out the individual email sends in parallel.
 *
 * <p>Sizing rationale:
 * <ul>
 *   <li>{@code corePoolSize = 4}  — up to 4 bulk-email jobs can run concurrently
 *       without waiting for a free thread.</li>
 *   <li>{@code maxPoolSize  = 8}  — burst headroom; beyond this new jobs queue.</li>
 *   <li>{@code queueCapacity = 50} — jobs that can be queued while all threads are busy.</li>
 * </ul>
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    /** Pool for long-running bulk jobs (bulk email, bulk PDF): {@code @Async(AsyncConfig.BULK_EXECUTOR)}. */
    public static final String BULK_EXECUTOR = "bulkExecutor";

    /** Default {@code @Async} pool — short tasks (e-sign finalisation, audit writes, API-key usage). */
    @Override
    @Bean(name = "taskExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * Separate pool so hours-long bulk jobs can never starve the short tasks above (a signed
     * document's finalisation would otherwise wait behind four running campaigns).
     */
    @Bean(name = BULK_EXECUTOR)
    public Executor bulkExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("bulk-job-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
