package com.braify.feature.cloudconfig.controller;

import com.braify.feature.cloudconfig.dto.OrgCloudConfigRequest;
import com.braify.feature.cloudconfig.dto.OrgCloudConfigResponse;
import com.braify.feature.user.model.AppUser;
import com.braify.feature.cloudconfig.service.OrgCloudConfigService;
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
 * REST endpoints for managing an organisation's cloud storage configuration.
 *
 * <p>Base path: {@code /api/organizations/{orgId}/cloud-config}
 *
 * <p>Access rules:
 * <ul>
 *   <li>PLATFORM_ADMIN — can read and write any organisation's config.</li>
 *   <li>ORG_ADMIN      — can only read and write their own organisation's config.</li>
 * </ul>
 */
@Slf4j
@Tag(name = "Organisation Cloud Config",
     description = "Manage cloud storage credentials (AWS / Azure / GCP) for an organisation. " +
                   "Sensitive fields are always returned masked. " +
                   "PLATFORM_ADMIN can manage any org; ORG_ADMIN can only manage their own.")
@RestController
@RequestMapping("/api/organizations/{orgId}/cloud-config")
@RequiredArgsConstructor
public class OrgCloudConfigController {

    private final OrgCloudConfigService cloudConfigService;

    private AppUser currentUser(Authentication auth) {
        return ((UserDetailsImpl) auth.getPrincipal()).getAppUser();
    }

    // ── GET ───────────────────────────────────────────────────────────────────

    @Operation(
        summary = "Get cloud storage configuration",
        description = "Returns the organisation's cloud provider configuration. " +
                      "`configured: false` is returned when no config has been saved yet. " +
                      "Credential fields (`accessKey`, `secretKey`) are always masked."
    )
    @ApiResponse(responseCode = "200", description = "Cloud config (or unconfigured state)")
    @ApiResponse(responseCode = "403", description = "Access denied")
    @GetMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ResponseEntity<OrgCloudConfigResponse> getCloudConfig(
            @Parameter(description = "Organisation ID") @PathVariable String orgId,
            Authentication auth) {
        log.debug("GET /api/organizations/{}/cloud-config caller='{}'", orgId, currentUser(auth).getEmail());
        return ResponseEntity.ok(cloudConfigService.getCloudConfig(orgId, currentUser(auth)));
    }

    // ── PUT ───────────────────────────────────────────────────────────────────

    @Operation(
        summary = "Update cloud storage configuration",
        description = "Replaces all cloud config fields for the organisation. " +
                      "Pass `null` for any credential field to clear it. " +
                      "Supported providers: `AWS`, `AZURE`, `GCP`.\n\n" +
                      "**accessKey / secretKey** — stored encrypted; returned masked in GET responses.\n\n" +
                      "**allowedFileTypes** — lowercase extensions without dots, e.g. `[\"pdf\", \"jpg\"]`.\n\n" +
                      "**maxUploadSizeMb** — must be greater than zero when provided.\n\n" +
                      "**presignedUrlExpiration** — minutes; must be greater than zero when provided."
    )
    @ApiResponse(responseCode = "200", description = "Updated cloud config")
    @ApiResponse(responseCode = "400", description = "Validation error (invalid provider / negative size)")
    @ApiResponse(responseCode = "403", description = "Access denied")
    @PutMapping
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ResponseEntity<OrgCloudConfigResponse> updateCloudConfig(
            @Parameter(description = "Organisation ID") @PathVariable String orgId,
            @Valid @RequestBody OrgCloudConfigRequest req,
            Authentication auth) {
        log.info("PUT /api/organizations/{}/cloud-config by '{}'", orgId, currentUser(auth).getEmail());
        ResponseEntity<OrgCloudConfigResponse> result = ResponseEntity.ok(cloudConfigService.updateCloudConfig(orgId, req, currentUser(auth)));
        log.info("Cloud config updated for org '{}'", orgId);
        return result;
    }

    // ── POST /test ────────────────────────────────────────────────────────────

    @Operation(
        summary = "Test cloud storage connectivity",
        description = "Attempts a lightweight connectivity check against the configured cloud provider using the stored credentials. " +
                      "Returns `{ success: true/false, message: \"...\" }`. " +
                      "The stored credentials are decrypted in-memory and used only for this test — they are never returned in the response."
    )
    @ApiResponse(responseCode = "200", description = "Test result (success or failure details)")
    @ApiResponse(responseCode = "403", description = "Access denied")
    @PostMapping("/test")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN','ORG_ADMIN')")
    public ResponseEntity<java.util.Map<String, Object>> testConnectivity(
            @Parameter(description = "Organisation ID") @PathVariable String orgId,
            Authentication auth) {
        log.info("POST /api/organizations/{}/cloud-config/test by '{}'", orgId, currentUser(auth).getEmail());
        return ResponseEntity.ok(cloudConfigService.testConnectivity(orgId, currentUser(auth)));
    }
}
