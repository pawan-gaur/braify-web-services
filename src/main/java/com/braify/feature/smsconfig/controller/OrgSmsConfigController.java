package com.braify.feature.smsconfig.controller;

import com.braify.feature.smsconfig.dto.OrgSmsConfigRequest;
import com.braify.feature.smsconfig.dto.OrgSmsConfigResponse;
import com.braify.feature.smsconfig.service.OrgSmsConfigService;
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
 * Per-organisation outbound SMS provider configuration.
 * PLATFORM_ADMIN may manage any org; ORG_ADMIN is restricted to their own.
 */
@Slf4j
@Tag(name = "SMS Configuration",
     description = "Per-organisation SMS provider (Twilio / Vonage) credentials.")
@RestController
@RequestMapping("/api/organizations/{orgId}/sms-config")
@RequiredArgsConstructor
public class OrgSmsConfigController {

    private final OrgSmsConfigService service;

    private AppUser caller(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
    }

    @Operation(summary = "Get SMS provider config for an organisation (secrets masked)")
    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ResponseEntity<OrgSmsConfigResponse> get(@PathVariable String orgId, Authentication auth) {
        return ResponseEntity.ok(service.getSmsConfig(orgId, caller(auth)));
    }

    @Operation(summary = "Create or update the SMS provider config for an organisation")
    @PutMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ResponseEntity<OrgSmsConfigResponse> update(@PathVariable String orgId,
                                                       @RequestBody OrgSmsConfigRequest req,
                                                       Authentication auth) {
        return ResponseEntity.ok(service.updateSmsConfig(orgId, req, caller(auth)));
    }

    @Operation(summary = "Send a test SMS using the org's effective (resolved) SMS config")
    @PostMapping("/test")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ResponseEntity<Map<String, Object>> test(@PathVariable String orgId,
                                                     @RequestBody(required = false) OrgSmsConfigRequest req,
                                                     Authentication auth) {
        return ResponseEntity.ok(service.sendTest(orgId, req, caller(auth)));
    }
}
