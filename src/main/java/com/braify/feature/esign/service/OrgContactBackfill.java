package com.braify.feature.esign.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Seeds the recipient address book from existing e-sign documents on first startup
 * after this feature ships. Idempotent: {@link OrgContactService#backfillIfEmpty()}
 * runs only while {@code org_contacts} is empty.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrgContactBackfill {

    private final OrgContactService contactService;

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        try {
            contactService.backfillIfEmpty();
        } catch (Exception e) {
            log.warn("OrgContact backfill skipped due to error: {}", e.getMessage());
        }
    }
}
