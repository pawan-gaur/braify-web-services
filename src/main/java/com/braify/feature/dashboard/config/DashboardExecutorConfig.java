package com.braify.feature.dashboard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small bounded thread pool used to fan out the dashboard's many independent,
 * IO-bound MongoDB queries concurrently. The dashboard issues dozens of queries per
 * request; running them in parallel turns the total latency from the SUM of every
 * round-trip into roughly the SLOWEST single round-trip.
 */
@Configuration
public class DashboardExecutorConfig {

    @Bean(name = "dashboardExecutor", destroyMethod = "shutdown")
    public ExecutorService dashboardExecutor() {
        AtomicInteger n = new AtomicInteger();
        return Executors.newFixedThreadPool(24, r -> {
            Thread t = new Thread(r, "dashboard-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }
}
