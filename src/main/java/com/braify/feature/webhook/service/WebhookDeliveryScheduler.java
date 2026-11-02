package com.braify.feature.webhook.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sends due webhook deliveries every few seconds (rows are claimed atomically — multi-node safe). */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDeliveryScheduler {

    private final WebhookService webhookService;

    @Scheduled(fixedDelayString = "${app.webhooks.poll-ms:10000}", initialDelay = 30_000)
    public void run() {
        try {
            int n = webhookService.deliverDue(50);
            if (n > 0) log.debug("Webhooks: processed {} deliveries", n);
        } catch (Exception e) {
            log.error("Webhook delivery run failed: {}", e.getMessage(), e);
        }
    }
}
