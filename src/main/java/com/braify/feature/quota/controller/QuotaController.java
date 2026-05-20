package com.braify.feature.quota.controller;

import com.braify.feature.quota.dto.QuotaConfigRequest;
import com.braify.feature.quota.dto.QuotaConfigResponse;
import com.braify.feature.quota.dto.UsageResponse;
import com.braify.feature.user.model.AppUser;
import com.braify.security.UserDetailsImpl;
import com.braify.feature.quota.service.QuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Tag(name = "Usage Quotas", description = "View and override usage quota limits (max users, docs/month, storage, API calls) and fetch monthly usage history. Use -1 for unlimited.")
@RestController
@RequestMapping("/api/organizations/{orgId}/quota")
@RequiredArgsConstructor
public class QuotaController {

    private final QuotaService quotaService;

    private AppUser currentUser(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
    }

    // ── Config ────────────────────────────────────────────────────────────────

    @Operation(
        summary = "Get quota configuration",
        description = "Returns the current quota limits alongside the live usage values for the current month. " +
                      "A limit of `-1` means unlimited. " +
                      "PLATFORM_ADMIN can read any org; ORG_ADMIN can only read their own."
    )
    @ApiResponse(responseCode = "200", description = "Quota configuration with current usage")
    @ApiResponse(responseCode = "403", description = "Access denied")
    @GetMapping("/config")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ResponseEntity<QuotaConfigResponse> getConfig(
            @Parameter(description = "Organisation ID") @PathVariable String orgId,
            Authentication auth) {
        log.debug("GET /api/organizations/{}/quota/config caller='{}'", orgId, currentUser(auth).getEmail());
        assertAccess(orgId, currentUser(auth));
        return ResponseEntity.ok(quotaService.getConfigResponse(orgId));
    }

    @Operation(
        summary = "Override quota limits",
        description = "Overrides individual quota limits for the organisation, independent of the subscription plan defaults. " +
                      "PLATFORM_ADMIN only.\n\n" +
                      "Send `-1` for any field to make that dimension unlimited.\n\n" +
                      "Body: `{ maxUsers, maxDocsPerMonth, maxStorageMb, maxApiCallsPerMonth }`"
    )
    @ApiResponse(responseCode = "200", description = "Updated quota configuration")
    @ApiResponse(responseCode = "429", description = "Quota already exceeded (returned during enforcement, not here)")
    @PutMapping("/config")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<QuotaConfigResponse> updateConfig(
            @Parameter(description = "Organisation ID") @PathVariable String orgId,
            @Valid @RequestBody QuotaConfigRequest req,
            Authentication auth) {
        log.info("PUT /api/organizations/{}/quota/config by '{}'", orgId, currentUser(auth).getEmail());
        quotaService.overrideQuota(orgId, req, currentUser(auth).getEmail());
        log.info("Quota config updated for org '{}'", orgId);
        return ResponseEntity.ok(quotaService.getConfigResponse(orgId));
    }

    // ── Usage ─────────────────────────────────────────────────────────────────

    @Operation(
        summary = "Get usage history",
        description = "Returns monthly usage records in reverse-chronological order (newest first). " +
                      "Each record covers one calendar month and tracks: `docsGenerated`, `esignSent`, `storageMb`, `apiCalls`. " +
                      "PLATFORM_ADMIN can view any org; ORG_ADMIN can only view their own."
    )
    @ApiResponse(responseCode = "200", description = "List of monthly usage records")
    @GetMapping("/usage")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ResponseEntity<List<UsageResponse>> getUsage(
            @Parameter(description = "Organisation ID") @PathVariable String orgId,
            @Parameter(description = "Number of past months to return (default 6)") @RequestParam(defaultValue = "6") int months,
            Authentication auth) {
        log.debug("GET /api/organizations/{}/quota/usage months={} caller='{}'", orgId, months, currentUser(auth).getEmail());
        assertAccess(orgId, currentUser(auth));
        return ResponseEntity.ok(quotaService.getUsageHistory(orgId, months));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private void assertAccess(String orgId, AppUser caller) {
        if (caller.getRole() == AppUser.Role.PLATFORM_ADMIN) return;
        if (!orgId.equals(caller.getOrganizationId())) {
            throw new AccessDeniedException("You can only view your own organisation's quota.");
        }
    }
}
