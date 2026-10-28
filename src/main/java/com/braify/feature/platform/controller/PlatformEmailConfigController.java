package com.braify.feature.platform.controller;

import com.braify.feature.emailconfig.dto.OrgEmailConfigRequest;
import com.braify.feature.emailconfig.dto.OrgEmailConfigResponse;
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

import java.util.Map;

/**
 * Platform-wide default email provider configuration.
 *
 * <p>Restricted to PLATFORM_ADMIN. Organisations without their own email config
 * fall back to this default (and finally to the built-in Resend credentials).
 */
@Slf4j
@Tag(name = "Platform Email Default",
     description = "Platform-wide default email provider inherited by orgs that don't configure their own. PLATFORM_ADMIN only.")
@RestController
@RequestMapping("/api/platform/email-config")
@RequiredArgsConstructor
public class PlatformEmailConfigController {

    private final PlatformProviderDefaultsService service;

    private AppUser caller(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl ud) return ud.getAppUser();
        return null;
    }

    @Operation(summary = "Get the platform default email provider config (secrets masked)")
    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<OrgEmailConfigResponse> get() {
        return ResponseEntity.ok(service.getEmailConfig());
    }

    @Operation(summary = "Create or update the platform default email provider config")
    @PutMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<OrgEmailConfigResponse> update(@RequestBody OrgEmailConfigRequest req,
                                                         Authentication auth) {
        return ResponseEntity.ok(service.updateEmailConfig(req, caller(auth)));
    }

    @Operation(summary = "Send a test email using the platform default email config")
    @PostMapping("/test")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, Object>> test(@RequestBody(required = false) OrgEmailConfigRequest req,
                                                     Authentication auth) {
        return ResponseEntity.ok(service.sendEmailTest(req, caller(auth)));
    }
}
