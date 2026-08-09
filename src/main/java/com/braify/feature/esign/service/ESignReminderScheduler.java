package com.braify.feature.esign.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly sweep that emails automatic signing reminders. The heavy lifting (candidate query,
 * per-org policy resolution, per-signatory timing/cap, sending) lives in
 * {@link ESignDocumentService#runAutomaticReminderSweep()} so it can share the same
 * reminder-dispatch logic as the manual "send reminder now" endpoint.
 *
 * <p>Runs an hour after the previous run finishes; each tick is idempotent (next-reminder
 * times are re-derived from persisted counters), so a skipped or slow tick just resumes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ESignReminderScheduler {

    private final ESignDocumentService documentService;

    @Scheduled(fixedDelay = 3_600_000) // every 1 hour
    public void sendDueReminders() {
        try {
            documentService.runAutomaticReminderSweep();
        } catch (Exception e) {
            // Never let a scheduler tick die permanently on one bad document/org.
            log.error("[ESignReminder] Automatic reminder sweep failed: {}", e.getMessage(), e);
        }
    }
}
