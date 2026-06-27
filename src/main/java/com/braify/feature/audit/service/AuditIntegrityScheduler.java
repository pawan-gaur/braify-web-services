package com.braify.feature.audit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Daily tamper-evidence sweep over the audit-log hash chain. Logs an ERROR-level
 * alert if any record/link is broken so it surfaces in monitoring; PLATFORM_ADMIN
 * can also verify on demand via {@code GET /api/audit-logs/verify}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditIntegrityScheduler {

    private final AuditLogService auditLogService;

    /** Runs at 02:30 every day. */
    @Scheduled(cron = "0 30 2 * * *")
    public void verifyDaily() {
        try {
            Map<String, Object> report = auditLogService.verifyIntegrity(200_000);
            if (Boolean.FALSE.equals(report.get("intact"))) {
                log.error("AUDIT INTEGRITY ALERT — tamper detected in audit log: {}", report);
            } else {
                log.info("Audit integrity check passed: {}", report);
            }
        } catch (Exception e) {
            log.error("Audit integrity check failed to run: {}", e.getMessage(), e);
        }
    }
}
