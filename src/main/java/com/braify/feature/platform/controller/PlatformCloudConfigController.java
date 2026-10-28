package com.braify.feature.platform.controller;

import com.braify.feature.cloudconfig.dto.OrgCloudConfigRequest;
import com.braify.feature.cloudconfig.dto.OrgCloudConfigResponse;
import com.braify.feature.platform.service.PlatformProviderDefaultsService;
import com.braify.feature.user.model.AppUser;
import com.braify.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Platform-wide default cloud storage configuration.
 * Organisations without their own cloud config fall back to this default. PLATFORM_ADMIN only.
 */
@Slf4j
@Tag(name = "Platform Cloud Default",
     description = "Platform-wide default cloud storage inherited by orgs without their own. PLATFORM_ADMIN only.")
@RestController
@RequestMapping("/api/platform/cloud-config")
@RequiredArgsConstructor
public class PlatformCloudConfigController {

    private final PlatformProviderDefaultsService service;

    private AppUser caller(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl ud) return ud.getAppUser();
        return null;
    }

    @GetMapping
    @Operation(summary = "Get the platform default cloud storage config (credentials masked)")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<OrgCloudConfigResponse> get() {
        return ResponseEntity.ok(service.getCloudConfig());
    }

    @PutMapping
    @Operation(summary = "Create or update the platform default cloud storage config")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<OrgCloudConfigResponse> update(@RequestBody OrgCloudConfigRequest req, Authentication auth) {
        return ResponseEntity.ok(service.updateCloudConfig(req, caller(auth)));
    }
}
