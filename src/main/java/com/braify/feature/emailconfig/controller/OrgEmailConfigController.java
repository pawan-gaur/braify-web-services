package com.braify.feature.emailconfig.controller;

import com.braify.feature.emailconfig.dto.OrgEmailConfigRequest;
import com.braify.feature.emailconfig.dto.OrgEmailConfigResponse;
import com.braify.feature.emailconfig.service.OrgEmailConfigService;
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
 * Per-organisation outbound email provider configuration.
 *
 * <p>PLATFORM_ADMIN may manage any org; ORG_ADMIN is restricted to their own
 * (enforced again in the service). Secrets are masked in responses.
 */
@Slf4j
@Tag(name = "Email Configuration",
     description = "Per-organisation email provider (Resend / SendGrid / Mailgun / SMTP) credentials.")
@RestController
@RequestMapping("/api/organizations/{orgId}/email-config")
@RequiredArgsConstructor
public class OrgEmailConfigController {

    private final OrgEmailConfigService service;

    private AppUser caller(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
    }

    @Operation(summary = "Get email provider config for an organisation (secrets masked)")
    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ResponseEntity<OrgEmailConfigResponse> get(@PathVariable String orgId, Authentication auth) {
        return ResponseEntity.ok(service.getEmailConfig(orgId, caller(auth)));
    }

    @Operation(summary = "Create or update the email provider config for an organisation")
    @PutMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ResponseEntity<OrgEmailConfigResponse> update(@PathVariable String orgId,
                                                         @RequestBody OrgEmailConfigRequest req,
                                                         Authentication auth) {
        return ResponseEntity.ok(service.updateEmailConfig(orgId, req, caller(auth)));
    }

    @Operation(summary = "Send a test email using the org's effective (resolved) email config")
    @PostMapping("/test")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ResponseEntity<Map<String, Object>> test(@PathVariable String orgId,
                                                     @RequestBody(required = false) OrgEmailConfigRequest req,
                                                     Authentication auth) {
        return ResponseEntity.ok(service.sendTest(orgId, req, caller(auth)));
    }
}
