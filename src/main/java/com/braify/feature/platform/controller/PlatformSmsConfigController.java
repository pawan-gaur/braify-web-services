package com.braify.feature.platform.controller;

import com.braify.feature.platform.service.PlatformProviderDefaultsService;
import com.braify.feature.smsconfig.dto.OrgSmsConfigRequest;
import com.braify.feature.smsconfig.dto.OrgSmsConfigResponse;
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

import java.util.Map;

/**
 * Platform-wide default SMS provider configuration.
 * Organisations without their own SMS config fall back to this default. PLATFORM_ADMIN only.
 */
@Slf4j
@Tag(name = "Platform SMS Default",
     description = "Platform-wide default SMS provider inherited by orgs without their own. PLATFORM_ADMIN only.")
@RestController
@RequestMapping("/api/platform/sms-config")
@RequiredArgsConstructor
public class PlatformSmsConfigController {

    private final PlatformProviderDefaultsService service;

    private AppUser caller(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl ud) return ud.getAppUser();
        return null;
    }

    @GetMapping
    @Operation(summary = "Get the platform default SMS provider config (secrets masked)")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<OrgSmsConfigResponse> get() {
        return ResponseEntity.ok(service.getSmsConfig());
    }

    @PutMapping
    @Operation(summary = "Create or update the platform default SMS provider config")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<OrgSmsConfigResponse> update(@RequestBody OrgSmsConfigRequest req, Authentication auth) {
        return ResponseEntity.ok(service.updateSmsConfig(req, caller(auth)));
    }

    @PostMapping("/test")
    @Operation(summary = "Send a test SMS using the platform default SMS config")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, Object>> test(@RequestBody(required = false) OrgSmsConfigRequest req,
                                                     Authentication auth) {
        return ResponseEntity.ok(service.sendSmsTest(req, caller(auth)));
    }
}
