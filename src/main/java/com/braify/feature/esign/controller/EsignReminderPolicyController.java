package com.braify.feature.esign.controller;

import com.braify.feature.esign.dto.EsignReminderPolicyRequest;
import com.braify.feature.esign.model.EsignReminderPolicy;
import com.braify.feature.esign.service.EsignReminderPolicyService;
import com.braify.feature.user.model.AppUser;
import com.braify.security.UserDetailsImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Org-level automatic e-sign reminder policy. PLATFORM_ADMIN can manage any org;
 * ORG_ADMIN can manage only their own.
 */
@Slf4j
@Tag(name = "E-Sign Reminder Policy",
     description = "Configure the organisation's automatic e-sign reminder schedule (first delay, repeat interval, cap).")
@RestController
@RequestMapping("/api/organizations/{orgId}/esign-reminder-policy")
@RequiredArgsConstructor
public class EsignReminderPolicyController {

    private final EsignReminderPolicyService policyService;

    private AppUser currentUser(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
    }

    @Operation(summary = "Get the organisation's reminder policy",
               description = "Returns the org's saved policy, or the built-in defaults (first reminder 24h after sending, then every 24h, up to 10).")
    @ApiResponse(responseCode = "200", description = "Reminder policy")
    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ResponseEntity<EsignReminderPolicy> get(
            @Parameter(description = "Organisation ID") @PathVariable String orgId,
            Authentication auth) {
        return ResponseEntity.ok(policyService.getPolicy(orgId, currentUser(auth)));
    }

    @Operation(summary = "Update the organisation's reminder policy")
    @ApiResponse(responseCode = "200", description = "Updated reminder policy")
    @ApiResponse(responseCode = "400", description = "Validation error (out-of-range hours/count)")
    @ApiResponse(responseCode = "403", description = "Access denied — ORG_ADMIN can only manage their own org")
    @PutMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ResponseEntity<EsignReminderPolicy> update(
            @Parameter(description = "Organisation ID") @PathVariable String orgId,
            @Valid @RequestBody EsignReminderPolicyRequest req,
            Authentication auth) {
        log.info("PUT /api/organizations/{}/esign-reminder-policy by '{}'", orgId, currentUser(auth).getEmail());
        return ResponseEntity.ok(policyService.updatePolicy(orgId, req, currentUser(auth)));
    }
}
