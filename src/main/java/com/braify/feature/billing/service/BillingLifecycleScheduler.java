package com.braify.feature.billing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly: ends expired trials and lapsed subscriptions (manual plans are never touched). */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingLifecycleScheduler {

    private final BillingService billingService;

    @Scheduled(fixedDelayString = "${app.billing.lifecycle-interval-ms:3600000}", initialDelay = 120_000)
    public void run() {
        try {
            int n = billingService.downgradeExpired();
            if (n > 0) log.info("Billing lifecycle: {} organisation(s) moved to Free", n);
        } catch (Exception e) {
            log.error("Billing lifecycle run failed: {}", e.getMessage(), e);
        }
    }
}
