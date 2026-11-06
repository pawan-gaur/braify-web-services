package com.braify.feature.esign.service;

import com.braify.feature.bulkemail.service.EmailRateLimiter;
import com.braify.feature.esign.config.ESignSendExecutorConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Runs several independent e-sign provider calls (one per signatory) concurrently on the
 * bounded {@link ESignSendExecutorConfig#ESIGN_SEND_EXECUTOR} pool and WAITS for all of them.
 *
 * <ul>
 *   <li>A single task runs inline on the caller thread, exactly as before (no pool, no pacing).</li>
 *   <li>With several tasks, each one first takes a slot from the shared {@link EmailRateLimiter}
 *       so a burst can never exceed the provider's per-second limit (shared with bulk email).</li>
 *   <li>Results come back in input order. If any task throws, the others still finish and the
 *       first failure (in input order) is rethrown unwrapped, as a sequential loop would surface it.</li>
 * </ul>
 * Tasks must only make the external call: keep mutations of the shared ESignDocument and its
 * {@code save()} on the caller thread.
 */
@Component
public class ESignSendFanOut {

    private final Executor         executor;
    private final EmailRateLimiter rateLimiter;

    public ESignSendFanOut(@Qualifier(ESignSendExecutorConfig.ESIGN_SEND_EXECUTOR) Executor executor,
                           EmailRateLimiter rateLimiter) {
        this.executor    = executor;
        this.rateLimiter = rateLimiter;
    }

    public <T> List<T> runAll(List<Supplier<T>> tasks) {
        if (tasks.isEmpty()) return List.of();
        if (tasks.size() == 1) return java.util.Collections.singletonList(tasks.get(0).get());

        List<CompletableFuture<T>> futures = tasks.stream()
                .map(t -> CompletableFuture.supplyAsync(() -> {
                    rateLimiter.acquire();
                    return t.get();
                }, executor))
                .toList();
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException ignored) {
            // allOf completes only after every task finished; surface the first failure below.
        }
        List<T> results = new java.util.ArrayList<>(futures.size());
        for (CompletableFuture<T> f : futures) {
            try {
                results.add(f.join());
            } catch (CompletionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof RuntimeException re) throw re;
                if (cause instanceof Error err) throw err;
                throw e;
            }
        }
        return results;
    }
}
