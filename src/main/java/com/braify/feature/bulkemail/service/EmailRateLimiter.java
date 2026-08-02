package com.braify.feature.bulkemail.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * A tiny, dependency-free pacing rate limiter shared across all bulk-email worker threads,
 * so the combined send rate to the email provider stays under its per-second limit no matter
 * how many campaigns/workers run concurrently.
 *
 * <p>It hands out "grant slots" spaced {@code 1/rate} seconds apart: each caller reserves the
 * next slot and sleeps until it arrives. Bursts queue rather than exceed the rate. Set
 * {@code bulkemail.rate-limit-per-second} to {@code 0} to disable throttling.
 */
@Component
public class EmailRateLimiter {

    private final boolean disabled;
    private final long    intervalNanos;
    private final Object  lock = new Object();
    private long          nextFreeNanos = System.nanoTime();

    public EmailRateLimiter(@Value("${bulkemail.rate-limit-per-second:8}") double perSecond) {
        this.disabled      = perSecond <= 0;
        this.intervalNanos = disabled ? 0 : (long) (1_000_000_000d / perSecond);
    }

    /** Blocks until the caller is allowed to send. */
    public void acquire() {
        if (disabled) return;
        long grantAt;
        synchronized (lock) {
            long now = System.nanoTime();
            grantAt = Math.max(now, nextFreeNanos);
            nextFreeNanos = grantAt + intervalNanos;
        }
        long sleep = grantAt - System.nanoTime();
        if (sleep > 0) {
            try {
                Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
