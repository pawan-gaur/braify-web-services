package com.braify.feature.branding.controller;

import com.braify.feature.branding.dto.OrgBrandingRequest;
import com.braify.feature.branding.dto.OrgBrandingResponse;
import com.braify.feature.user.model.AppUser;
import com.braify.security.UserDetailsImpl;
import com.braify.feature.branding.service.OrgBrandingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Organisation Branding", description = "Manage logo, primary colour, email sender name/reply-to, and custom footer text. PLATFORM_ADMIN can manage any org; ORG_ADMIN can only manage their own.")
@RestController
@RequestMapping("/api/organizations/{orgId}/branding")
@RequiredArgsConstructor
public class OrgBrandingController {

    private final OrgBrandingService brandingService;

    private AppUser currentUser(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
    }

    @Operation(
        summary = "Get organisation branding",
        description = "Returns branding settings for the specified organisation. " +
                      "The `configured` flag indicates whether any branding has been saved yet. " +
                      "The `logoBase64` field contains a data-URL (base64-encoded image)."
    )
    @ApiResponse(responseCode = "200", description = "Branding settings")
    @ApiResponse(responseCode = "403", description = "Access denied — ORG_ADMIN can only access their own org")
    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ResponseEntity<OrgBrandingResponse> getBranding(
            @Parameter(description = "Organisation ID") @PathVariable String orgId,
            Authentication auth) {
        return ResponseEntity.ok(brandingService.getBranding(orgId, currentUser(auth)));
    }

    @Operation(
        summary = "Update organisation branding",
        description = "Replaces all branding fields for the organisation. All fields are optional — send `null` to clear a field.\n\n" +
                      "**logoBase64** — base64 data-URL, e.g. `data:image/png;base64,...` (max ~2 MB)\n\n" +
                      "**primaryColor** — hex colour `#rrggbb`, injected as `--brand-color` CSS var in PDF templates\n\n" +
                      "**emailSenderName** — display name shown in From field of outgoing emails\n\n" +
                      "**emailReplyTo** — reply-to email address (must be valid format)\n\n" +
                      "**footerText** — plain text appended to every generated PDF footer (max 500 chars)"
    )
    @ApiResponse(responseCode = "200", description = "Updated branding settings")
    @ApiResponse(responseCode = "400", description = "Validation error (invalid hex colour / email / footer too long)")
    @ApiResponse(responseCode = "403", description = "Access denied")
    @PutMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ResponseEntity<OrgBrandingResponse> updateBranding(
            @Parameter(description = "Organisation ID") @PathVariable String orgId,
            @RequestBody OrgBrandingRequest req,
            Authentication auth) {
        return ResponseEntity.ok(brandingService.updateBranding(orgId, req, currentUser(auth)));
    }
}
