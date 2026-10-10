package com.braify.feature.bulkemail.migration;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Platform-admin visibility into (and manual re-run of) the bulk-email recipients migration. */
@RestController
@RequestMapping("/api/bulk-email/migration")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@Tag(name = "Bulk Email — Migration", description = "Status and re-run of the recipients-collection migration")
public class BulkEmailMigrationController {

    private final BulkEmailRowMigrationService migrationService;

    @Operation(summary = "Migration status",
               description = "Legacy campaigns left, campaigns migrated, documents moved, and any failures.")
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(migrationService.status());
    }

    @Operation(summary = "Re-run migration",
               description = "Migrates any remaining legacy campaigns. Idempotent and safe to repeat.")
    @PostMapping("/run")
    public ResponseEntity<BulkEmailRowMigrationService.RunReport> run() {
        return ResponseEntity.ok(migrationService.migrateAll());
    }
}
