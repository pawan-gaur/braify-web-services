package com.braify.feature.storage.controller;

import com.braify.feature.storage.service.LegacyFileMigration;
import com.braify.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin/storage")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@Tag(name = "Admin storage", description = "Platform Admin: move legacy in-database files into storage")
public class AdminStorageController {

    private final LegacyFileMigration migration;

    @PostMapping("/migrate-legacy")
    @Operation(summary = "Start the legacy file migration",
               description = "Runs in the background. `dryRun=true` (default) only counts what would move. " +
                             "Poll `GET /api/admin/storage/migrate-legacy` for the report. Safe to re-run.")
    public ResponseEntity<Map<String, Object>> start(@RequestParam(defaultValue = "true") boolean dryRun,
                                                     @AuthenticationPrincipal UserDetailsImpl principal) {
        log.info("POST /api/admin/storage/migrate-legacy dryRun={} by '{}'", dryRun, principal.getUsername());
        if (!migration.start(dryRun))
            throw new IllegalStateException("A migration is already running");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(migration.lastReport().toMap());
    }

    @GetMapping("/migrate-legacy")
    @Operation(summary = "Report of the current / last migration run")
    public ResponseEntity<Map<String, Object>> report() {
        LegacyFileMigration.Report r = migration.lastReport();
        return r == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(r.toMap());
    }
}
