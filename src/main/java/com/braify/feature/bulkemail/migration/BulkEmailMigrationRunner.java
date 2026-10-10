package com.braify.feature.bulkemail.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Migrates all legacy bulk-email jobs at startup. ApplicationRunners finish before
 * {@code ApplicationReadyEvent}, so this completes before {@code BulkEmailScheduler} resumes any
 * interrupted campaign. Requests that arrive meanwhile are still safe: anything touching a
 * not-yet-migrated job migrates it first ({@link BulkEmailRowMigrationService#ensureMigrated}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BulkEmailMigrationRunner implements ApplicationRunner {

    private final BulkEmailRowMigrationService migrationService;

    @Value("${bulkemail.migration.enabled:true}")
    private boolean enabled;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.warn("[BulkEmailMigration] Startup migration disabled (bulkemail.migration.enabled=false); "
                    + "legacy jobs will be migrated lazily on first access");
            return;
        }
        try {
            migrationService.migrateAll();
        } catch (Exception e) {
            // Never block startup — each job is still migrated lazily on first access.
            log.error("[BulkEmailMigration] Startup migration run aborted: {}", e.getMessage(), e);
        }
    }
}
