package com.braify.feature.quota.service;

import com.braify.feature.quota.repository.OrgUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Periodically purges OrgUsage records that are older than the configured
 * retention window.  Keeps storage bounded while preserving enough history
 * for trend dashboards (default: 24 months).
 *
 * <p>Runs once per day at 03:00 server time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuotaCleanupScheduler {

    private final OrgUsageRepository usageRepository;

    /** How many months of usage history to retain. Configurable via application.yml. */
    @Value("${quota.history-retention-months:24}")
    private int retentionMonths;

    @Scheduled(cron = "0 0 3 * * *")   // 03:00 every day
    public void purgeOldUsageRecords() {
        LocalDate cutoff = LocalDate.now().minusMonths(retentionMonths);
        int cutoffYear  = cutoff.getYear();
        int cutoffMonth = cutoff.getMonthValue();

        long deleted = usageRepository.deleteByYearLessThanOrYearEqualsAndMonthLessThan(
                cutoffYear, cutoffYear, cutoffMonth);

        if (deleted > 0) {
            log.info("[QuotaCleanup] Purged {} OrgUsage records older than {}/{} ({} month retention)",
                    deleted, cutoffYear, cutoffMonth, retentionMonths);
        } else {
            log.debug("[QuotaCleanup] No OrgUsage records to purge");
        }
    }
}
