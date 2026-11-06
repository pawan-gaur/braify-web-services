package com.braify.feature.esign.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Small bounded pool that fans out the independent e-sign provider calls of ONE request
 * (invitation / reminder emails + SMS to several signatories) so they run concurrently
 * instead of back to back. Callers always wait for every task before returning, so the
 * HTTP response and error semantics are unchanged.
 *
 * <p>The caller's SecurityContext and MDC are copied onto the worker thread so Mongo
 * {@code @CreatedBy} auditing and log correlation behave exactly as on the request thread.
 * When the queue is full the caller runs the task itself (never rejects).
 */
@Configuration
public class ESignSendExecutorConfig {

    public static final String ESIGN_SEND_EXECUTOR = "esignSendExecutor";

    @Bean(name = ESIGN_SEND_EXECUTOR)
    public ThreadPoolTaskExecutor esignSendExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("esign-send-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(contextPropagatingDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    private static TaskDecorator contextPropagatingDecorator() {
        return runnable -> {
            SecurityContext callerCtx = SecurityContextHolder.getContext();
            Map<String, String> callerMdc = MDC.getCopyOfContextMap();
            return () -> {
                SecurityContext previousCtx = SecurityContextHolder.getContext();
                Map<String, String> previousMdc = MDC.getCopyOfContextMap();
                try {
                    SecurityContextHolder.setContext(callerCtx);
                    if (callerMdc != null) MDC.setContextMap(callerMdc); else MDC.clear();
                    runnable.run();
                } finally {
                    SecurityContextHolder.setContext(previousCtx);
                    if (previousMdc != null) MDC.setContextMap(previousMdc); else MDC.clear();
                }
            };
        };
    }
}
