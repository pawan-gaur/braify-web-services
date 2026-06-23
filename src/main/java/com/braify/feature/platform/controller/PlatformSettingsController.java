package com.braify.feature.platform.controller;

import com.braify.feature.platform.model.PlatformSettings;
import com.braify.feature.platform.service.PlatformSettingsService;
import com.braify.feature.user.model.AppUser;
import com.braify.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Platform-wide settings (security & access policies).
 *
 * <p>All endpoints are restricted to PLATFORM_ADMIN. Settings apply to every
 * organisation, org admin, admin and user across the platform.
 */
@Slf4j
@Tag(name = "Platform Settings",
     description = "Global security & access policies inherited by every tenant. PLATFORM_ADMIN only.")
@RestController
@RequestMapping("/api/platform/settings")
@RequiredArgsConstructor
public class PlatformSettingsController {

    private final PlatformSettingsService service;

    private AppUser caller(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl ud) return ud.getAppUser();
        return null;
    }

    @Operation(summary = "Get platform settings",
               description = "Returns the single platform-wide settings document (security & access policies). PLATFORM_ADMIN only.")
    @ApiResponse(responseCode = "200", description = "Current platform settings")
    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<PlatformSettings> get() {
        log.debug("GET /api/platform/settings");
        return ResponseEntity.ok(service.getSettings());
    }

    @Operation(summary = "Update platform settings",
               description = "Replaces the security & access policies. Values are sanitised server-side and the " +
                             "change is written to the audit log. Applies platform-wide. PLATFORM_ADMIN only.")
    @ApiResponse(responseCode = "200", description = "Updated platform settings")
    @PutMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<PlatformSettings> update(@RequestBody PlatformSettings body, Authentication auth) {
        AppUser caller = caller(auth);
        log.info("PUT /api/platform/settings by '{}'", caller != null ? caller.getEmail() : "unknown");
        return ResponseEntity.ok(service.updateSettings(body, caller));
    }
}
