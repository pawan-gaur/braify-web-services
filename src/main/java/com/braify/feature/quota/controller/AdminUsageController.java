package com.braify.feature.quota.controller;

import com.braify.feature.quota.dto.OrgUsageSummary;
import com.braify.feature.quota.service.QuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Platform-wide usage overview — per-organisation current-month consumption.
 * PLATFORM_ADMIN only.
 */
@Slf4j
@Tag(name = "Platform Usage", description = "Org-wide usage overview (users, documents, storage, API calls, emails). PLATFORM_ADMIN only.")
@RestController
@RequestMapping("/api/admin/usage")
@RequiredArgsConstructor
public class AdminUsageController {

    private final QuotaService quotaService;

    @Operation(summary = "Get usage for all organisations",
               description = "Returns current-month usage per organisation. PLATFORM_ADMIN only.")
    @ApiResponse(responseCode = "200", description = "Per-organisation usage list")
    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<List<OrgUsageSummary>> getAllOrgUsage() {
        log.debug("GET /api/admin/usage");
        return ResponseEntity.ok(quotaService.getAllOrgUsage());
    }
}
