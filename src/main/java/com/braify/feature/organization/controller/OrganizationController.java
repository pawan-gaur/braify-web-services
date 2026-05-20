package com.braify.feature.organization.controller;

import com.braify.feature.organization.dto.OrgFeaturesRequest;
import com.braify.feature.organization.dto.OrgFeaturesResponse;
import com.braify.feature.organization.dto.OrganizationRequest;
import com.braify.feature.organization.dto.SubscriptionRequest;
import com.braify.feature.organization.dto.SubscriptionResponse;
import com.braify.feature.organization.model.Organization;
import com.braify.security.UserDetailsImpl;
import com.braify.feature.organization.service.OrganizationService;
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

import java.util.List;

@Slf4j
@Tag(name = "Organizations", description = "CRUD, feature management, and subscription management for tenant organisations. Requires PLATFORM_ADMIN role.")
@RestController
@RequestMapping("/api/organizations")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService orgService;

    /** Extracts the acting user's email from the JWT principal. */
    private String performedBy(Authentication auth) {
        if (auth == null) return "system";
        Object principal = auth.getPrincipal();
        if (principal instanceof UserDetailsImpl ud) return ud.getUsername();
        return auth.getName();
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Operation(summary = "List all organisations",
               description = "Returns every organisation (active and inactive). PLATFORM_ADMIN only.")
    @ApiResponse(responseCode = "200", description = "List of organisations")
    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public List<Organization> getAll() {
        log.debug("GET /api/organizations");
        return orgService.findAll();
    }

    @Operation(summary = "Search organisations",
               description = "Full-text search on name and code. PLATFORM_ADMIN only.")
    @GetMapping("/search")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public List<Organization> search(
            @Parameter(description = "Search query (matches name or code)") @RequestParam(defaultValue = "") String q) {
        log.debug("GET /api/organizations/search q='{}'", q);
        return orgService.search(q);
    }

    @Operation(summary = "Get organisation by ID", description = "PLATFORM_ADMIN only.")
    @ApiResponse(responseCode = "200", description = "Organisation found")
    @ApiResponse(responseCode = "404", description = "Organisation not found")
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Organization getById(@Parameter(description = "Organisation ID") @PathVariable String id) {
        log.debug("GET /api/organizations/{}", id);
        return orgService.findById(id);
    }

    @Operation(summary = "Create organisation",
               description = "Creates a new tenant organisation. Code must be unique and is immutable after creation.")
    @ApiResponse(responseCode = "200", description = "Organisation created")
    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Organization> create(@Valid @RequestBody OrganizationRequest req,
                                               Authentication auth) {
        log.info("POST /api/organizations name='{}' by '{}'", req.getName(), performedBy(auth));
        ResponseEntity<Organization> result = ResponseEntity.ok(orgService.create(req, performedBy(auth)));
        log.info("Organisation created: id='{}'", result.getBody() != null ? result.getBody().getId() : "unknown");
        return result;
    }

    @Operation(summary = "Update organisation",
               description = "Updates name, description, and active status. Code cannot be changed.")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Organization> update(
            @Parameter(description = "Organisation ID") @PathVariable String id,
            @Valid @RequestBody OrganizationRequest req,
            Authentication auth) {
        log.info("PUT /api/organizations/{} by '{}'", id, performedBy(auth));
        ResponseEntity<Organization> result = ResponseEntity.ok(orgService.update(id, req, performedBy(auth)));
        log.info("Organisation '{}' updated", id);
        return result;
    }

    @Operation(summary = "Soft-delete organisation",
               description = "Marks the organisation as deleted. Users and data are retained.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Void> delete(@Parameter(description = "Organisation ID") @PathVariable String id) {
        log.info("DELETE /api/organizations/{}", id);
        orgService.delete(id);
        log.info("Organisation '{}' deleted", id);
        return ResponseEntity.noContent().build();
    }

    // ── Feature management ────────────────────────────────────────────────────

    @Operation(summary = "Get enabled features",
               description = "Returns the list of feature keys currently enabled for the organisation (e.g. PDF_TEMPLATES, E_SIGN).")
    @GetMapping("/{id}/features")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<OrgFeaturesResponse> getFeatures(
            @Parameter(description = "Organisation ID") @PathVariable String id) {
        return ResponseEntity.ok(orgService.getFeatures(id));
    }

    @Operation(summary = "Replace enabled features",
               description = "Replaces the entire feature list for the organisation. Send an empty array to disable all features.\n\nBody: `{ \"features\": [\"PDF_TEMPLATES\", \"E_SIGN\"] }`")
    @PutMapping("/{id}/features")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<OrgFeaturesResponse> updateFeatures(
            @Parameter(description = "Organisation ID") @PathVariable String id,
            @Valid @RequestBody OrgFeaturesRequest req,
            Authentication auth) {
        log.info("PUT /api/organizations/{}/features features={} by '{}'", id, req.getFeatures(), performedBy(auth));
        ResponseEntity<OrgFeaturesResponse> result = ResponseEntity.ok(orgService.updateFeatures(id, req.getFeatures(), performedBy(auth)));
        log.info("Features updated for org '{}'", id);
        return result;
    }

    // ── Subscription ──────────────────────────────────────────────────────────

    @Operation(summary = "Get subscription plan",
               description = "Returns the current subscription tier (FREE / PROFESSIONAL / ENTERPRISE) along with the plan's default quota values.")
    @GetMapping("/{id}/subscription")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<SubscriptionResponse> getSubscription(
            @Parameter(description = "Organisation ID") @PathVariable String id) {
        return ResponseEntity.ok(orgService.getSubscription(id));
    }

    @Operation(summary = "Assign subscription plan",
               description = "Upgrades or downgrades the plan tier and optionally sets an expiry date. " +
                             "Automatically resets quota limits to the new plan's defaults. " +
                             "Pass `planExpiresAt: null` for no expiry.\n\n" +
                             "Body: `{ \"subscriptionPlan\": \"PROFESSIONAL\", \"planExpiresAt\": \"2027-01-01\" }`")
    @PutMapping("/{id}/subscription")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<SubscriptionResponse> assignSubscription(
            @Parameter(description = "Organisation ID") @PathVariable String id,
            @Valid @RequestBody SubscriptionRequest req,
            Authentication auth) {
        log.info("PUT /api/organizations/{}/subscription plan='{}' by '{}'", id, req.getSubscriptionPlan(), performedBy(auth));
        ResponseEntity<SubscriptionResponse> result = ResponseEntity.ok(orgService.assignSubscription(id, req, performedBy(auth)));
        log.info("Subscription plan '{}' assigned to org '{}'", req.getSubscriptionPlan(), id);
        return result;
    }
}
